/*
 * GameServer.java
 *
 * Holds all authoritative game state and processes messages from both
 * ClientHandler threads.
 *
 * Thread-safety: handleMessage() is called from two threads simultaneously.
 * All shared state (boards, fleets, turn, phase, readyCount) is protected
 * by synchronized(this). CHAT is handled before the lock so it is never
 * delayed by ongoing game-logic processing.
 *
 * I/O: never hold the lock while doing I/O. Use WriterThread.send() to
 * enqueue messages — it returns immediately.
 *
 * ASSIGN timing: sendAssign() must be called after handshake() returns,
 * because WriterThread and ClientHandler share the same OutputStream.
 */

import java.net.Socket;

/**
 * Authoritative game-state container and message dispatcher
 *
 * Called concurrently by two ClientHandler threads; all shared state (boards,
 * fleets, turn, phase, readyCount) is protected by synchronized(this). CHAT is
 * dispatched before acquiring the lock so it is never delayed by FIRE processing.
 * All outbound I/O goes through WriterThread.send which is non-blocking, so no
 * lock is held during a network write. Phase transitions: WAITING -> PLAYING -> DONE
 */
public class GameServer
{
    private static final int PHASE_WAITING = 0;
    private static final int PHASE_PLAYING = 1;
    private static final int PHASE_DONE = 2;

    private final Board[] boards = new Board[2];
    private final Fleet[] fleets = new Fleet[2];
    private final WriterThread[] writers = new WriterThread[2];
    private final String[] names = {"Player 1", "Player 2"};
    private final Socket[] sockets;

    private int phase = PHASE_WAITING;
    private int turn = 1;
    private int readyCount = 0;

    /**
     * @param s1 socket for player 1
     * @param s2 socket for player 2
     * @param w1 writer thread for player 1
     * @param w2 writer thread for player 2
     */
    public GameServer(Socket s1, Socket s2, WriterThread w1, WriterThread w2)
    {
        sockets = new Socket[]{s1, s2};
        writers[0] = w1;
        writers[1] = w2;
    }

    // -----------------------------------------------------------------------
    // Called by ClientHandler immediately after handshake() returns
    // -----------------------------------------------------------------------

    /**
     * Sends ASSIGN (and WAITING for player 1) to the given player.
     * Safe to call without the game-logic lock — only touches per-player
     * outboxes, which are already thread-safe.
     *
     * Must only be called after WebSocketUtil.handshake() has returned for
     * this player's socket.
     */
    public void sendAssign(int playerNum)
    {
        int idx = playerNum - 1;
        writers[idx].send(Message.assignJson(playerNum));
        if(playerNum == 1)
        {
            writers[0].send(Message.waitingJson());
        }
    }

    // -----------------------------------------------------------------------
    // Called by ClientHandler threads
    // -----------------------------------------------------------------------

    /**
     * Processes one message from the given player.
     *
     * Handle CHAT outside any lock — forward it to both clients immediately.
     * For all other types, synchronize on 'this' and dispatch on msg.type:
     *   "READY" → handleReady
     *   "FIRE"  → handleFire
     *   anything else → send ERROR back to the sender
     */
    public void handleMessage(int playerNum, Message msg)
    {
        // CHAT bypasses the lock so it is never delayed by FIRE processing
        if("CHAT".equals(msg.type))
        {
            broadcast(Message.chatJson(names[playerNum - 1], msg.text != null ? msg.text : ""));
            return;
        }

        synchronized(this)
        {
            switch(msg.type)
            {
                case "READY":
                    handleReady(playerNum, msg);
                    break;
                case "FIRE":
                    handleFire(playerNum, msg);
                    break;
                default:
                    writers[playerNum - 1].send(
                        Message.errorJson("Unknown message type: " + msg.type));
            }
        }
    }

    /**
     * Called when a client disconnects or throws an IOException.
     * Notifies the other player and initiates shutdown.
     * Must be idempotent — safe to call twice if both sockets close at once.
     */
    public void handleDisconnect(int playerNum)
    {
        synchronized(this)
        {
            if(phase == PHASE_DONE) return;
            phase = PHASE_DONE;
        }
        int otherIdx = (playerNum == 1) ? 1 : 0;
        writers[otherIdx].send(Message.opponentDisconnectedJson());
        shutdown();
    }

    // -----------------------------------------------------------------------
    // Private message handlers — call only while holding synchronized(this)
    // -----------------------------------------------------------------------

    private void handleReady(int playerNum, Message msg)
    {
        if(msg.name != null && !msg.name.isBlank())
        {
            names[playerNum - 1] = msg.name;
        }
        readyCount++;
        if(readyCount < 2) return;

        phase = PHASE_PLAYING;
        turn = 1;

        ShipPlacementGenerator gen = ShipPlacementGenerator.getInstance();
        ShipPlacementGenerator.ShipPlacements p1 = gen.generatePlacements(null, false);
        ShipPlacementGenerator.ShipPlacements p2 = gen.generatePlacements(null, false);

        boards[0] = new Board(p1.board);
        boards[1] = new Board(p2.board);
        fleets[0] = new Fleet(p1.shipRows, p1.shipCols);
        fleets[1] = new Fleet(p2.shipRows, p2.shipCols);

        System.out.println("[Server] Game started. Player 1: " + names[0] + ", Player 2: " + names[1]);

        writers[0].send(Message.gameStartJson(boards[0].shipLayoutToJson(), turn));
        writers[1].send(Message.gameStartJson(boards[1].shipLayoutToJson(), turn));
    }

    private void handleFire(int playerNum, Message msg)
    {
        if(phase != PHASE_PLAYING)
        {
            writers[playerNum - 1].send(Message.errorJson("Game is not in progress."));
            return;
        }
        if(playerNum != turn)
        {
            writers[playerNum - 1].send(Message.errorJson("It is not your turn."));
            return;
        }

        int row = msg.row;
        int col = msg.col;

        if(row < 0 || row >= GameConfiguration.BOARD_SIZE
                || col < 0 || col >= GameConfiguration.BOARD_SIZE)
        {
            writers[playerNum - 1].send(Message.errorJson("Invalid coordinates."));
            return;
        }

        int defenderIdx = (playerNum == 1) ? 1 : 0;
        Board defenderBoard = boards[defenderIdx];
        Fleet defenderFleet = fleets[defenderIdx];

        if(defenderBoard.isTargeted(row, col))
        {
            writers[playerNum - 1].send(Message.errorJson("Cell already targeted."));
            return;
        }

        boolean hit = defenderBoard.fireShot(row, col);
        String sunkShip = null;
        if(hit)
        {
            sunkShip = defenderFleet.registerHit(row, col);
        }

        broadcast(Message.shotResultJson(playerNum, row, col, hit, sunkShip));

        if(defenderFleet.allSunk())
        {
            phase = PHASE_DONE;
            broadcast(Message.gameOverJson(playerNum, defenderBoard.fullStateToJson()));
            shutdown();
            return;
        }

        // Hit lets the same player shoot again; only miss advances turn
        if(!hit)
        {
            turn = (turn == 1) ? 2 : 1;
            broadcast(Message.turnChangeJson(turn));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Enqueues json in both clients' outboxes. */
    private void broadcast(String json)
    {
        writers[0].send(json);
        writers[1].send(json);
    }

    /** Signals both WriterThreads to flush and exit. */
    private void shutdown()
    {
        writers[0].shutdown();
        writers[1].shutdown();
    }
}