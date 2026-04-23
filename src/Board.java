/**
 * One player's 10x10 Battleship grid
 *
 * Maintains hiddenBoard (actual ship layout, never revealed to the opponent) and
 * playerView (opponent-visible hits and misses, also used to enforce already-targeted).
 * Adapted from Lab 4; text-display methods removed, two JSON serialization methods added
 */
public class Board
{
    private final int size;
    private final char[][] hiddenBoard; // actual ship positions
    private final char[][] playerView; // hits/misses visible to the opponent

    /**
     * @param hiddenBoard BOARD_SIZE x BOARD_SIZE grid with SHIP and WATER chars
     */
    public Board(char[][] hiddenBoard)
    {
        this.size = hiddenBoard.length;
        this.hiddenBoard = hiddenBoard;
        this.playerView = new char[size][size];
        for(int r = 0; r < size; r++)
            for(int c = 0; c < size; c++)
                playerView[r][c] = GameConfiguration.WATER;
    }

    /**
     * Fires a shot at (row, col). Returns true if hit, false if miss
     * @throws IllegalArgumentException if out of bounds
     * @throws IllegalStateException if already targeted
     */
    public boolean fireShot(int row, int col)
    {
        if(row < 0 || row >= size || col < 0 || col >= size)
            throw new IllegalArgumentException("Out of bounds: " + row + "," + col);
        if(playerView[row][col] != GameConfiguration.WATER)
            throw new IllegalStateException("Already targeted: " + row + "," + col);

        if(hiddenBoard[row][col] == GameConfiguration.SHIP)
        {
            playerView[row][col] = GameConfiguration.HIT;
            hiddenBoard[row][col] = GameConfiguration.HIT;
            return true;
        }
        playerView[row][col] = GameConfiguration.MISS;
        return false;
    }

    /** Returns true if the cell at (row, col) has already been fired upon. */
    public boolean isTargeted(int row, int col)
    {
        return playerView[row][col] != GameConfiguration.WATER;
    }

    /** Returns the side length of this board */
    public int getSize()
    {
        return size;
    }

    /**
     * Returns the opponent-visible state of a single cell (WATER, HIT, or MISS)
     *
     * @param r row index 0-based
     * @param c column index 0-based
     */
    public char getPlayerViewCell(int r, int c)
    {
        return playerView[r][c];
    }

    // -----------------------------------------------------------------------
    // JSON serialization — new for Lab 5
    // -----------------------------------------------------------------------

    /**
     * Returns a JSON 2-D array representing where this player's ships are placed.
     * Encoding: 1 = ship cell, 0 = water.
     *
     * Used in the GAME_START message so the client can draw the player's own fleet.
     *
     * Example output for a 3×3 board with a ship at (0,0) and (0,1):
     *   [[1,1,0],[0,0,0],[0,0,0]]
     *
     * Build the string with a StringBuilder; do not use Arrays.deepToString
     * (it adds spaces that are harder to parse on the client side).
     */
    public String shipLayoutToJson()
    {
        StringBuilder sb = new StringBuilder("[");
        for(int r = 0; r < size; r++)
        {
            if(r > 0) sb.append(",");
            sb.append("[");
            for(int c = 0; c < size; c++)
            {
                if(c > 0) sb.append(",");
                char cell = hiddenBoard[r][c];
                sb.append((cell == GameConfiguration.SHIP || cell == GameConfiguration.HIT) ? 1 : 0);
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Returns a JSON 2-D array encoding the full revealed state of this board.
     * Used in the GAME_OVER message so both players can see the final board.
     *
     * Encoding:
     *   0 = water (never targeted)
     *   1 = ship cell that was never hit (revealed at game end)
     *   2 = hit
     *   3 = miss
     */
    public String fullStateToJson()
    {
        StringBuilder sb = new StringBuilder("[");
        for(int r = 0; r < size; r++)
        {
            if(r > 0) sb.append(",");
            sb.append("[");
            for(int c = 0; c < size; c++)
            {
                if(c > 0) sb.append(",");
                char view = playerView[r][c];
                char hidden = hiddenBoard[r][c];
                int code;
                if(view == GameConfiguration.HIT)
                    code = 2;
                else if(view == GameConfiguration.MISS)
                    code = 3;
                else if(hidden == GameConfiguration.SHIP)
                    code = 1;
                else
                    code = 0;
                sb.append(code);
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }
}
