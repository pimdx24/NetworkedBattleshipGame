/**
 * Represents a single protocol message between server and client
 *
 * All messages share a type field; additional fields depend on the type.
 * Absent numeric fields default to -1; absent strings to null.
 * Provides parse() to deserialize client messages and static factory methods
 * to build server-to-client JSON strings. No external JSON library is used
 */
public class Message
{
    // -----------------------------------------------------------------------
    // Fields — not every field is present in every message type.
    // Absent numeric fields should default to -1; absent strings to null.
    // -----------------------------------------------------------------------

    public String type;
    public int row = -1;
    public int col = -1;
    public String text = null;
    public String name = null;

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    /**
     * Parses a JSON string received from a client into a Message.
     *
     * At minimum, handle the types a client can send:
     *   READY  — may include a "name" field
     *   FIRE   — includes "row" and "col" (integers)
     *   CHAT   — includes "text"
     *
     * If the string cannot be parsed, return a Message whose type is "UNKNOWN".
     *
     * @param json the raw JSON string from a WebSocket frame
     * @return a populated Message object
     */
    public static Message parse(String json)
    {
        Message m = new Message();
        if(json == null || json.isBlank())
        {
            m.type = "UNKNOWN";
            return m;
        }
        m.type = extractString(json, "type");
        if(m.type == null)
        {
            m.type = "UNKNOWN";
            return m;
        }
        switch(m.type)
        {
            case "FIRE":
                m.row = extractInt(json, "row");
                m.col = extractInt(json, "col");
                break;
            case "CHAT":
                m.text = extractString(json, "text");
                break;
            case "READY":
                m.name = extractString(json, "name");
                break;
            default:
                break;
        }
        return m;
    }

    /** Extracts a string value for the given key
     *  @return null if not found 
     */
    private static String extractString(String json, String key)
    {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if(idx == -1)  
            return null;
        idx += search.length();
        while(idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == ':')) 
            idx++;
        if(idx >= json.length()) 
            return null;
        if(json.startsWith("null", idx)) 
            return null;
        if(json.charAt(idx) != '"') 
            return null;
        idx++;
        StringBuilder sb = new StringBuilder();
        while(idx < json.length() && json.charAt(idx) != '"')
        {
            if(json.charAt(idx) == '\\' && idx + 1 < json.length())
            {
                idx++;
                char esc = json.charAt(idx);
                switch(esc)
                {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(esc);  break;
                }
            }
            else
            {
                sb.append(json.charAt(idx));
            }
            idx++;
        }
        return sb.toString();
    }

    /** Extracts an integer value for the given key
     *  @return -1 if not found 
     */
    private static int extractInt(String json, String key)
    {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if(idx == -1)  
            return -1;
        idx += search.length();
        while(idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == ':')) 
            idx++;
        if(idx >= json.length()) 
            return -1;
        int start = idx;
        if(idx < json.length() && json.charAt(idx) == '-') 
            idx++;
        while(idx < json.length() && Character.isDigit(json.charAt(idx))) 
            idx++;
        if(idx == start) 
            return -1;
        try
        {
            return Integer.parseInt(json.substring(start, idx));
        }
        catch(NumberFormatException e)
        {
            return -1;
        }
    }

    /** Escapes characters that are illegal inside a JSON string value. */
    private static String escapeJson(String s)
    {
        if(s == null) 
            return "";
        StringBuilder sb = new StringBuilder();
        for(char c : s.toCharArray())
        {
            switch(c)
            {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);      break;
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // JSON builders  (server → client)
    //
    // Each method returns a JSON string ready to hand to WriterThread.send().
    // Field names must exactly match the protocol table in the README.
    // -----------------------------------------------------------------------
    
    /** {"type":"ASSIGN","playerNumber":&lt;n&gt;} */
    public static String assignJson(int playerNumber)
    {
        return "{\"type\":\"ASSIGN\",\"playerNumber\":" + playerNumber + "}";
    }

    /** {"type":"WAITING"} */
    public static String waitingJson()
    {
        return "{\"type\":\"WAITING\"}";
    }

    /** {"type":"GAME_START","myBoard":&lt;2d array&gt;,"turn":&lt;n&gt;} */
    public static String gameStartJson(String myBoardJson, int turn)
    {
        return "{\"type\":\"GAME_START\",\"myBoard\":" + myBoardJson + ",\"turn\":" + turn + "}";
    }

    /** {"type":"SHOT_RESULT","shooter":&lt;n&gt;,"row":&lt;r&gt;,"col":&lt;c&gt;,"hit":&lt;bool&gt;,"sunkShip":&lt;name|null&gt;} */
    public static String shotResultJson(int shooter, int row, int col, boolean hit, String sunkShip)
    {
        String sunkShipStr = (sunkShip == null) ? "null" : "\"" + escapeJson(sunkShip) + "\"";
        return "{\"type\":\"SHOT_RESULT\",\"shooter\":" + shooter
             + ",\"row\":" + row
             + ",\"col\":" + col
             + ",\"hit\":" + hit
             + ",\"sunkShip\":" + sunkShipStr + "}";
    }

    /** {"type":"TURN_CHANGE","turn":&lt;n&gt;} */
    public static String turnChangeJson(int turn)
    {
        return "{\"type\":\"TURN_CHANGE\",\"turn\":" + turn + "}";
    }

    /** {"type":"CHAT","from":"&lt;from&gt;","text":"&lt;text&gt;"} */
    public static String chatJson(String from, String text)
    {
        return "{\"type\":\"CHAT\",\"from\":\"" + escapeJson(from)
             + "\",\"text\":\"" + escapeJson(text) + "\"}";
    }

    /** {"type":"GAME_OVER","winner":&lt;n&gt;,"finalBoard":&lt;2d array&gt;} */
    public static String gameOverJson(int winner, String finalBoardJson)
    {
        return "{\"type\":\"GAME_OVER\",\"winner\":" + winner
             + ",\"finalBoard\":" + finalBoardJson + "}";
    }

    /** {"type":"OPPONENT_DISCONNECTED"} */
    public static String opponentDisconnectedJson()
    {
        return "{\"type\":\"OPPONENT_DISCONNECTED\"}";
    }

    /** {"type":"ERROR","message":"&lt;msg&gt;"} */
    public static String errorJson(String msg)
    {
        return "{\"type\":\"ERROR\",\"message\":\"" + escapeJson(msg) + "\"}";
    }
}
