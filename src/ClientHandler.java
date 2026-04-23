/*
 * ClientHandler.java
 *
 * One instance per connected client.  Runs on its own Thread.
 *
 * Responsibilities (in order):
 *   1. Perform the WebSocket handshake on the raw OutputStream.
 *   2. Once the handshake is complete, call server.sendAssign() — it is only
 *      safe to write WebSocket frames after the 101 response has been sent.
 *   3. Loop reading WebSocket frames, parse each into a Message, and forward
 *      to GameServer.handleMessage().
 *   4. On disconnect or I/O error, notify GameServer.handleDisconnect().
 */

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

/**
 * Reader thread for one connected client
 *
 * Performs the WebSocket handshake, sends ASSIGN, then loops reading frames
 * and forwarding each parsed Message to GameServer.handleMessage. On any exit
 * the finally block calls GameServer.handleDisconnect to notify the other player.
 * One thread per client prevents readFrame from stalling the other player
 */
public class ClientHandler extends Thread
{
    private final Socket socket;
    private final int playerNum;
    private final GameServer server;

    /**
     * @param socket    the accepted client socket
     * @param playerNum 1 or 2
     * @param server    the shared game-logic object
     */
    public ClientHandler(Socket socket, int playerNum, GameServer server)
    {
        this.socket = socket;
        this.playerNum = playerNum;
        this.server = server;
        setDaemon(true);
    }

    /** Handshake then ASSIGN then read loop; calls handleDisconnect on exit */
    @Override
    public void run()
    {
        try
        {
            InputStream in = socket.getInputStream();

            // Handshake must complete before ASSIGN is sent since both share the same output stream
            WebSocketUtil.handshake(in, socket.getOutputStream());
            server.sendAssign(playerNum);

            System.out.println("[Server] Player " + playerNum + " connected.");

            String frame;
            while((frame = WebSocketUtil.readFrame(in)) != null)
            {
                Message msg = Message.parse(frame);
                server.handleMessage(playerNum, msg);
            }
        }
        catch(IOException e)
        {
            System.err.println("[Server] Player " + playerNum + " I/O error: " + e.getMessage());
        }
        finally
        {
            System.out.println("[Server] Player " + playerNum + " disconnected.");
            server.handleDisconnect(playerNum);
        }
    }
}
