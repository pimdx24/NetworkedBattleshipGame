import java.util.Random;
import java.util.Scanner;

/**
 * Generates ship placements for the Battleship game.
 *
 * In RANDOM mode (normal play), ships are placed randomly on the board.
 * In DETERMINISTIC mode (testing/autograder), placements are read from
 * standard input so that the autograder knows exactly where ships are.
 *
 * Placement format read from stdin (one line per ship, in SHIP_NAMES order):
 *   row col orientation
 * where row and col are 0-indexed integers and orientation is H or V.
 *
 * Example (for the default 5 ships on a 10x10 board):
 *   0 0 H      -- Carrier at (0,0) horizontal
 *   2 1 V      -- Battleship at (2,1) vertical
 *   4 3 H      -- Cruiser at (4,3) horizontal
 *   6 0 H      -- Submarine at (6,0) horizontal
 *   8 5 V      -- Destroyer at (8,5) vertical
 *
 * Do not modify this class for your submission.
 */
public class ShipPlacementGenerator {
    private static ShipPlacementGenerator instance = new ShipPlacementGenerator();
    public static ShipPlacementGenerator getInstance() { return instance; }

    private Random random;

    private ShipPlacementGenerator() {
        random = new Random();
    }

    /**
     * Returns a 2D board array with ships placed on it.
     * Each cell is either GameConfiguration.WATER or GameConfiguration.SHIP.
     *
     * Also returns the ship placement details so the Game can track
     * individual ship hit-points for sinking announcements.
     *
     * @param scanner the single Scanner connected to stdin
     * @param deterministic if true, read placements from stdin; otherwise random
     * @return a ShipPlacements object containing the board and per-ship info
     */
    public ShipPlacements generatePlacements(Scanner scanner, boolean deterministic) {
        int size = GameConfiguration.BOARD_SIZE;
        char[][] board = new char[size][size];
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++)
                board[r][c] = GameConfiguration.WATER;

        String[] names = GameConfiguration.SHIP_NAMES;
        int[] sizes = GameConfiguration.SHIP_SIZES;
        int[][] shipRows = new int[names.length][];
        int[][] shipCols = new int[names.length][];

        if (deterministic) {
            for (int i = 0; i < names.length; i++) {
                int row = Integer.parseInt(scanner.next());
                int col = Integer.parseInt(scanner.next());
                String orient = scanner.next();
                boolean horizontal = orient.equalsIgnoreCase("H");

                shipRows[i] = new int[sizes[i]];
                shipCols[i] = new int[sizes[i]];

                for (int j = 0; j < sizes[i]; j++) {
                    int r = horizontal ? row : row + j;
                    int c = horizontal ? col + j : col;
                    board[r][c] = GameConfiguration.SHIP;
                    shipRows[i][j] = r;
                    shipCols[i][j] = c;
                }
            }
            if (scanner.hasNextLine()) scanner.nextLine(); // consume trailing newline
        } else {
            for (int i = 0; i < names.length; i++) {
                boolean placed = false;
                while (!placed) {
                    boolean horizontal = random.nextBoolean();
                    int maxRow = horizontal ? size : size - sizes[i];
                    int maxCol = horizontal ? size - sizes[i] : size;
                    int row = random.nextInt(maxRow);
                    int col = random.nextInt(maxCol);

                    // Check if space is clear
                    boolean clear = true;
                    for (int j = 0; j < sizes[i] && clear; j++) {
                        int r = horizontal ? row : row + j;
                        int c = horizontal ? col + j : col;
                        if (board[r][c] != GameConfiguration.WATER) clear = false;
                    }

                    if (clear) {
                        shipRows[i] = new int[sizes[i]];
                        shipCols[i] = new int[sizes[i]];
                        for (int j = 0; j < sizes[i]; j++) {
                            int r = horizontal ? row : row + j;
                            int c = horizontal ? col + j : col;
                            board[r][c] = GameConfiguration.SHIP;
                            shipRows[i][j] = r;
                            shipCols[i][j] = c;
                        }
                        placed = true;
                    }
                }
            }
        }

        return new ShipPlacements(board, shipRows, shipCols);
    }

    /**
     * Container for ship placement results.
     */
    public static class ShipPlacements {
        public final char[][] board;
        public final int[][] shipRows;  // shipRows[i][j] = row of j-th cell of ship i
        public final int[][] shipCols;  // shipCols[i][j] = col of j-th cell of ship i

        public ShipPlacements(char[][] board, int[][] shipRows, int[][] shipCols) {
            this.board = board;
            this.shipRows = shipRows;
            this.shipCols = shipCols;
        }
    }
}
