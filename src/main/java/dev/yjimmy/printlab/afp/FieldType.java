package dev.yjimmy.printlab.afp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Names for common MO:DCA structured field type IDs. The three ID bytes are class (always
 * {@code D3} for MO:DCA), type ({@code A8} Begin, {@code A9} End, others for descriptors, data
 * and includes) and category ({@code A8} document, {@code AD} named page group, {@code AF} page,
 * ...). This list isn't complete; {@link #nameOf(int)} shows any other ID in hex.
 */
public enum FieldType {
    BDT(0xD3A8A8, "Begin Document"),
    EDT(0xD3A9A8, "End Document"),
    BNG(0xD3A8AD, "Begin Named Page Group"),
    ENG(0xD3A9AD, "End Named Page Group"),
    BPG(0xD3A8AF, "Begin Page"),
    EPG(0xD3A9AF, "End Page"),
    BAG(0xD3A8C9, "Begin Active Environment Group"),
    EAG(0xD3A9C9, "End Active Environment Group"),
    BRG(0xD3A8C6, "Begin Resource Group"),
    ERG(0xD3A9C6, "End Resource Group"),
    BRS(0xD3A8CE, "Begin Resource"),
    ERS(0xD3A9CE, "End Resource"),
    BDG(0xD3A8C4, "Begin Document Environment Group"),
    EDG(0xD3A9C4, "End Document Environment Group"),
    BDI(0xD3A8A7, "Begin Document Index"),
    EDI(0xD3A9A7, "End Document Index"),
    BFM(0xD3A8CD, "Begin Form Map"),
    EFM(0xD3A9CD, "End Form Map"),
    BMM(0xD3A8CC, "Begin Medium Map"),
    EMM(0xD3A9CC, "End Medium Map"),
    BMO(0xD3A8DF, "Begin Overlay"),
    EMO(0xD3A9DF, "End Overlay"),
    BPS(0xD3A85F, "Begin Page Segment"),
    EPS(0xD3A95F, "End Page Segment"),
    BPT(0xD3A89B, "Begin Presentation Text Object"),
    EPT(0xD3A99B, "End Presentation Text Object"),
    BIM(0xD3A8FB, "Begin Image Object"),
    EIM(0xD3A9FB, "End Image Object"),
    BGR(0xD3A8BB, "Begin Graphics Object"),
    EGR(0xD3A9BB, "End Graphics Object"),
    BBC(0xD3A8EB, "Begin Bar Code Object"),
    EBC(0xD3A9EB, "End Bar Code Object"),
    BOC(0xD3A892, "Begin Object Container"),
    EOC(0xD3A992, "End Object Container"),
    TLE(0xD3A090, "Tag Logical Element"),
    IEL(0xD3B2A7, "Index Element"),
    PTX(0xD3EE9B, "Presentation Text Data"),
    PTD(0xD3B19B, "Presentation Text Data Descriptor"),
    IPD(0xD3EEFB, "Image Picture Data"),
    NOP(0xD3EEEE, "No Operation"),
    IMM(0xD3ABCC, "Invoke Medium Map"),
    IPO(0xD3AFD8, "Include Page Overlay"),
    IPS(0xD3AF5F, "Include Page Segment"),
    IOB(0xD3AFC3, "Include Object"),
    MCF(0xD3AB8A, "Map Coded Font"),
    MDD(0xD3A688, "Medium Descriptor"),
    PGD(0xD3A6AF, "Page Descriptor"),
    OBD(0xD3A66B, "Object Area Descriptor"),
    OBP(0xD3AC6B, "Object Area Position");

    private static final Map<Integer, FieldType> BY_ID = new HashMap<>();

    static {
        for (FieldType type : values()) {
            if (BY_ID.put(type.id, type) != null) {
                throw new ExceptionInInitializerError("Duplicate field type ID " + hex(type.id));
            }
        }
    }

    private final int id;
    private final String description;

    FieldType(int id, String description) {
        this.id = id;
        this.description = description;
    }

    /** The 3-byte type ID, e.g. {@code 0xD3A8A8}. */
    public int id() {
        return id;
    }

    /** The name from the MO:DCA reference, e.g. "Begin Document". */
    public String description() {
        return description;
    }

    public static Optional<FieldType> forId(int id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** The short name ({@code BDT}), or the ID as six hex digits ({@code D3EEFF}). */
    public static String nameOf(int id) {
        return forId(id).map(Enum::name).orElseGet(() -> hex(id));
    }

    /** The ID as six uppercase hex digits. */
    public static String hex(int id) {
        return String.format(Locale.ROOT, "%06X", id);
    }
}
