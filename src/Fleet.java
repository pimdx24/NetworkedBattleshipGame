/**
 * Tracks the damage status of one player's fleet
 *
 * Each ship is identified by its index in SHIP_NAMES. registerHit scans cell
 * coordinates to find which ship was hit, decrements its counter, and returns
 * the ship name if it just sank
 */
public class Fleet
{
    private final String[] shipNames;
    private final int[] shipSizes;
    private final int[][] shipRows;
    private final int[][] shipCols;
    private final int[] hitsRemaining;
    private final boolean[] sunk;
    private int shipsAfloat;

    /**
     * @param shipRows shipRows[i][j] is the row of cell j of ship i
     * @param shipCols shipCols[i][j] is the column of cell j of ship i
     */
    public Fleet(int[][] shipRows, int[][] shipCols)
    {
        this.shipNames = GameConfiguration.SHIP_NAMES;
        this.shipSizes = GameConfiguration.SHIP_SIZES;
        this.shipRows = shipRows;
        this.shipCols = shipCols;
        this.hitsRemaining = new int[shipNames.length];
        this.sunk = new boolean[shipNames.length];
        this.shipsAfloat = shipNames.length;

        for(int i = 0; i < shipNames.length; i++)
        {
            hitsRemaining[i] = shipSizes[i];
        }
    }

    /**
     * Registers a hit at (row, col). Returns the ship name if it just sank, null otherwise.
     */
    public String registerHit(int row, int col)
    {
        for(int i = 0; i < shipNames.length; i++)
        {
            if(sunk[i]) continue;
            for(int j = 0; j < shipRows[i].length; j++)
            {
                if(shipRows[i][j] == row && shipCols[i][j] == col)
                {
                    hitsRemaining[i]--;
                    if(hitsRemaining[i] == 0)
                    {
                        sunk[i] = true;
                        shipsAfloat--;
                        return shipNames[i];
                    }
                    return null;
                }
            }
        }
        return null;
    }

    /** Returns true if every ship has been sunk */
    public boolean allSunk()
    {
        return shipsAfloat == 0;
    }

    /** Returns the number of ships not yet fully hit */
    public int getShipsAfloat()
    {
        return shipsAfloat;
    }

    /** @param i ship index (0=Carrier, 1=Battleship, 2=Cruiser, 3=Submarine, 4=Destroyer) */
    public boolean isSunk(int i)
    {
        return sunk[i];
    }

    /** @param i ship index */
    public String getName(int i)
    {
        return shipNames[i];
    }

    /** @param i ship index */
    public int getSize(int i)
    {
        return shipSizes[i];
    }

    /** Returns the total number of ships */
    public int getShipCount()
    {
        return shipNames.length;
    }
}
