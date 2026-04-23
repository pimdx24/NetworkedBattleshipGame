/*
 * BattleshipServer.java
 *
 * Entry point.  Accepts exactly two client connections, wires up all
 * objects, starts all threads, and waits for the game to finish.
 *
 * Usage:
 *   java BattleshipServer          (default port 8080)
 *   java BattleshipServer 9090     (custom port)
 *
 * Important — do NOT pre-queue ASSIGN messages here.
 * ASSIGN is sent from inside ClientHandler, immediately after the WebSocket
 * handshake completes.  Sending it earlier risks writing a WebSocket frame
 * before the HTTP 101 upgrade response is finished, which corrupts the
 * connection and causes the browser to disconnect immediately.
 */

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Entry point for the Networked Battleship server
 *
 * Accepts exactly two WebSocket connections on the configured port (default 8080),
 * wires up all game objects and threads, then blocks until both ClientHandler
 * threads finish. Any further connection is rejected with an ERROR message.
 * Thread model: main thread, ClientHandler x2, WriterThread x2, daemon reject thread
 */
public class BattleshipServer
{
    private static final int DEFAULT_PORT = 8080;

    /**
     * @param args optionally a single argument: the port number to listen on
     * @throws IOException if the server socket cannot be opened
     */
    public static void main(String[] args) throws IOException
    {
        int port = DEFAULT_PORT;
        if(args.length > 0)
        {
            try
            {
                port = Integer.parseInt(args[0]);
            }
            catch(NumberFormatException e)
            {
                System.err.println("[Server] Invalid port: " + args[0] + " — using " + DEFAULT_PORT);
            }
        }

        try(ServerSocket serverSocket = new ServerSocket(port))
        {
            System.out.println("[Server] Starting on port " + port + " ...");
            System.out.println("[Server] Waiting for two players to connect ...");

            Socket s1 = serverSocket.accept();
            System.out.println("[Server] Player 1 connecting from " + s1.getRemoteSocketAddress());

            Socket s2 = serverSocket.accept();
            System.out.println("[Server] Player 2 connecting from " + s2.getRemoteSocketAddress());

            // Reject any further connections with an error message.
            Thread rejectThread = new Thread(() ->
            {
                while(!Thread.currentThread().isInterrupted())
                {
                    try
                    {
                        Socket extra = serverSocket.accept();
                        try
                        {
                            WebSocketUtil.handshake(extra.getInputStream(), extra.getOutputStream());
                            WebSocketUtil.writeFrame(extra.getOutputStream(),
                                Message.errorJson("Game is full. Only two players allowed."));
                        }
                        catch(IOException ignored) {}
                        try
                        {
                            extra.close();
                        }
                        catch(IOException ignored) {}
                    }
                    catch(IOException e)
                    {
                        break;
                    }
                }
            });
            rejectThread.setDaemon(true);
            rejectThread.start();

            LinkedBlockingQueue<String> outbox1 = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<String> outbox2 = new LinkedBlockingQueue<>();

            WriterThread w1 = new WriterThread(outbox1, s1.getOutputStream(), s1);
            WriterThread w2 = new WriterThread(outbox2, s2.getOutputStream(), s2);

            GameServer gameServer = new GameServer(s1, s2, w1, w2);

            ClientHandler ch1 = new ClientHandler(s1, 1, gameServer);
            ClientHandler ch2 = new ClientHandler(s2, 2, gameServer);

            w1.start();
            w2.start();
            ch1.start();
            ch2.start();

            ch1.join();
            ch2.join();

            System.out.println("[Server] Game over. Shutting down.");
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            System.err.println("[Server] Interrupted while waiting for game to end.");
        }
    }
}
