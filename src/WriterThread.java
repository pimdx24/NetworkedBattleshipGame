/*
 * WriterThread.java
 *
 * Drains a LinkedBlockingQueue<String> and writes each entry to the client's
 * OutputStream as a WebSocket frame. Having one dedicated writer per client
 * prevents concurrent writes on the same OutputStream, which would corrupt frames.
 *
 * Shutdown is signalled by placing POISON_PILL in the queue.
 */

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Dedicated writer thread for one connected client
 *
 * Drains a LinkedBlockingQueue of JSON strings and writes each as a WebSocket
 * frame. One thread per client prevents concurrent writes from interleaving
 * mid-frame. Shutdown by enqueuing POISON_PILL via shutdown(); the thread
 * delivers all already-queued messages first, then closes the socket which
 * unblocks the paired ClientHandler's readFrame call
 */
public class WriterThread extends Thread
{
    /** Sentinel value that causes this thread to flush remaining messages and exit */
    public static final String POISON_PILL = "\u0000SHUTDOWN\u0000";

    private final LinkedBlockingQueue<String> outbox;
    private final OutputStream out;
    private final Socket socket;

    /**
     * @param outbox queue of JSON strings to deliver; shared with producers
     * @param out    the client socket's output stream, written only by this thread
     * @param socket the client socket, closed on exit
     */
    public WriterThread(LinkedBlockingQueue<String> outbox, OutputStream out, Socket socket)
    {
        this.outbox = outbox;
        this.out = out;
        this.socket = socket;
        setDaemon(true);
    }

    /** Blocks on outbox.take, writes each string as a WebSocket frame, exits on POISON_PILL or IOException */
    @Override
    public void run()
    {
        try
        {
            while(true)
            {
                String msg = outbox.take();
                if(msg == POISON_PILL) break;   // reference equality — intentional
                try
                {
                    WebSocketUtil.writeFrame(out, msg);
                }
                catch(IOException e)
                {
                    break;
                }
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            try
            {
                socket.close();
            }
            catch(IOException ignored) {}
        }
    }

    /**
     * Enqueues a JSON string for delivery to this client.
     * Safe to call from any thread at any time.
     */
    public void send(String json)
    {
        outbox.offer(json);
    }

    /**
     * Signals this thread to stop after delivering any already-queued messages.
     * Returns immediately without waiting for the thread to exit.
     */
    public void shutdown()
    {
        outbox.offer(POISON_PILL);
    }
}
