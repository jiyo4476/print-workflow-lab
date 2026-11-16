package dev.yjimmy.printlab.afp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every structured field in the MO:DCA Reference (AFPC-0004-11, Appendix E, "MO:DCA Structured
 * Fields Sorted by Identifier"), in that table's order.
 *
 * <p>An ID is {@code D3TTCC}: class {@code D3} (MO:DCA), a type code such as {@code A8} Begin,
 * {@code A9} End, {@code A6} Descriptor or {@code EE} Data, and a category code such as
 * {@code A8} Document, {@code AD} Page Group or {@code AF} Page. A Begin field and its End
 * share the category code.
 *
 * <p>The reference marks some fields as kept for coexistence with older data streams (the
 * Format-1 fields and the IM Image object) or as obsolete; their descriptions say so. The enum
 * constant for an acronym with a hyphen drops it ({@link #PTD1} is PTD-1).
 * {@link #nameOf(int)} shows any ID not listed here in hex.
 */
public enum FieldType {
    MFC(0xD3A088, "MFC", "Medium Finishing Control"),
    TLE(0xD3A090, "TLE", "Tag Logical Element"),
    MCC(0xD3A288, "MCC", "Medium Copy Count"),
    OBD(0xD3A66B, "OBD", "Object Area Descriptor"),
    IID(0xD3A67B, "IID", "IM Image Input Descriptor (coexistence)"),
    MDD(0xD3A688, "MDD", "Medium Descriptor"),
    CDD(0xD3A692, "CDD", "Container Data Descriptor"),
    PTD1(0xD3A69B, "PTD-1", "Presentation Text Data Descriptor Format-1 (coexistence)"),
    PGD(0xD3A6AF, "PGD", "Page Descriptor"),
    GDD(0xD3A6BB, "GDD", "Graphics Data Descriptor"),
    FGD(0xD3A6C5, "FGD", "Form Environment Group Descriptor (obsolete)"),
    BDD(0xD3A6EB, "BDD", "Bar Code Data Descriptor"),
    IDD(0xD3A6FB, "IDD", "Image Data Descriptor"),
    IOC(0xD3A77B, "IOC", "IM Image Output Control (coexistence)"),
    MMC(0xD3A788, "MMC", "Medium Modification Control"),
    CTC(0xD3A79B, "CTC", "Composed Text Control (obsolete)"),
    PEC(0xD3A7A8, "PEC", "Presentation Environment Control"),
    PMC(0xD3A7AF, "PMC", "Page Modification Control"),
    BPS(0xD3A85F, "BPS", "Begin Page Segment"),
    BII(0xD3A87B, "BII", "Begin IM Image Object (coexistence)"),
    BOC(0xD3A892, "BOC", "Begin Object Container"),
    BPT(0xD3A89B, "BPT", "Begin Presentation Text Object"),
    BPF(0xD3A8A5, "BPF", "Begin Print File"),
    BDI(0xD3A8A7, "BDI", "Begin Document Index"),
    BDT(0xD3A8A8, "BDT", "Begin Document"),
    BNG(0xD3A8AD, "BNG", "Begin Named Page Group"),
    BPG(0xD3A8AF, "BPG", "Begin Page"),
    BGR(0xD3A8BB, "BGR", "Begin Graphics Object"),
    BDG(0xD3A8C4, "BDG", "Begin Document Environment Group"),
    BFG(0xD3A8C5, "BFG", "Begin Form Environment Group (obsolete)"),
    BRG(0xD3A8C6, "BRG", "Begin Resource Group"),
    BOG(0xD3A8C7, "BOG", "Begin Object Environment Group"),
    BAG(0xD3A8C9, "BAG", "Begin Active Environment Group"),
    BMM(0xD3A8CC, "BMM", "Begin Medium Map"),
    BFM(0xD3A8CD, "BFM", "Begin Form Map"),
    BRS(0xD3A8CE, "BRS", "Begin Resource"),
    BSG(0xD3A8D9, "BSG", "Begin Resource Environment Group"),
    BMO(0xD3A8DF, "BMO", "Begin Overlay"),
    BBC(0xD3A8EB, "BBC", "Begin Bar Code Object"),
    BIM(0xD3A8FB, "BIM", "Begin Image Object"),
    EPS(0xD3A95F, "EPS", "End Page Segment"),
    EII(0xD3A97B, "EII", "End IM Image Object (coexistence)"),
    EOC(0xD3A992, "EOC", "End Object Container"),
    EPT(0xD3A99B, "EPT", "End Presentation Text Object"),
    EPF(0xD3A9A5, "EPF", "End Print File"),
    EDI(0xD3A9A7, "EDI", "End Document Index"),
    EDT(0xD3A9A8, "EDT", "End Document"),
    ENG(0xD3A9AD, "ENG", "End Named Page Group"),
    EPG(0xD3A9AF, "EPG", "End Page"),
    EGR(0xD3A9BB, "EGR", "End Graphics Object"),
    EDG(0xD3A9C4, "EDG", "End Document Environment Group"),
    EFG(0xD3A9C5, "EFG", "End Form Environment Group (obsolete)"),
    ERG(0xD3A9C6, "ERG", "End Resource Group"),
    EOG(0xD3A9C7, "EOG", "End Object Environment Group"),
    EAG(0xD3A9C9, "EAG", "End Active Environment Group"),
    EMM(0xD3A9CC, "EMM", "End Medium Map"),
    EFM(0xD3A9CD, "EFM", "End Form Map"),
    ERS(0xD3A9CE, "ERS", "End Resource"),
    ESG(0xD3A9D9, "ESG", "End Resource Environment Group"),
    EMO(0xD3A9DF, "EMO", "End Overlay"),
    EBC(0xD3A9EB, "EBC", "End Bar Code Object"),
    EIM(0xD3A9FB, "EIM", "End Image Object"),
    MMT(0xD3AB88, "MMT", "Map Media Type"),
    MCF(0xD3AB8A, "MCF", "Map Coded Font"),
    MCD(0xD3AB92, "MCD", "Map Container Data"),
    MPT(0xD3AB9B, "MPT", "Map Presentation Text"),
    MPG(0xD3ABAF, "MPG", "Map Page"),
    MGO(0xD3ABBB, "MGO", "Map Graphics Object"),
    MDR(0xD3ABC3, "MDR", "Map Data Resource"),
    IMM(0xD3ABCC, "IMM", "Invoke Medium Map"),
    MMD(0xD3ABCD, "MMD", "Map Media Destination"),
    MPO(0xD3ABD8, "MPO", "Map Page Overlay"),
    MSU(0xD3ABEA, "MSU", "Map Suppression"),
    MBC(0xD3ABEB, "MBC", "Map Bar Code Object"),
    MIO(0xD3ABFB, "MIO", "Map Image Object"),
    OBP(0xD3AC6B, "OBP", "Object Area Position"),
    ICP(0xD3AC7B, "ICP", "IM Image Cell Position (coexistence)"),
    PGP1(0xD3ACAF, "PGP-1", "Page Position Format-1 (coexistence)"),
    PPO(0xD3ADC3, "PPO", "Preprocess Presentation Object"),
    IPS(0xD3AF5F, "IPS", "Include Page Segment"),
    IPG(0xD3AFAF, "IPG", "Include Page"),
    IOB(0xD3AFC3, "IOB", "Include Object"),
    IPO(0xD3AFD8, "IPO", "Include Page Overlay"),
    MPS(0xD3B15F, "MPS", "Map Page Segment"),
    MCF1(0xD3B18A, "MCF-1", "Map Coded Font Format-1 (coexistence)"),
    PTD(0xD3B19B, "PTD", "Presentation Text Data Descriptor"),
    PGP(0xD3B1AF, "PGP", "Page Position"),
    MMO(0xD3B1DF, "MMO", "Map Medium Overlay"),
    PFC(0xD3B288, "PFC", "Presentation Fidelity Control"),
    IEL(0xD3B2A7, "IEL", "Index Element"),
    LLE(0xD3B490, "LLE", "Link Logical Element"),
    IRD(0xD3EE7B, "IRD", "IM Image Raster Data (coexistence)"),
    OCD(0xD3EE92, "OCD", "Object Container Data"),
    PTX(0xD3EE9B, "PTX", "Presentation Text Data"),
    GAD(0xD3EEBB, "GAD", "Graphics Data"),
    BDA(0xD3EEEB, "BDA", "Bar Code Data"),
    NOP(0xD3EEEE, "NOP", "No Operation"),
    IPD(0xD3EEFB, "IPD", "Image Picture Data");

    private static final Map<Integer, FieldType> BY_ID = new HashMap<>();

    static {
        for (FieldType type : values()) {
            if (BY_ID.put(type.id, type) != null) {
                throw new ExceptionInInitializerError("Duplicate field type ID " + hex(type.id));
            }
        }
    }

    private final int id;
    private final String acronym;
    private final String description;

    FieldType(int id, String acronym, String description) {
        this.id = id;
        this.acronym = acronym;
        this.description = description;
    }

    /** The 3-byte type ID, e.g. {@code 0xD3A8A8}. */
    public int id() {
        return id;
    }

    /** The acronym from the MO:DCA reference, e.g. "BDT" or "PTD-1". */
    public String acronym() {
        return acronym;
    }

    /** The name from the MO:DCA reference, e.g. "Begin Document". */
    public String description() {
        return description;
    }

    public static Optional<FieldType> forId(int id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** The acronym ({@code BDT}), or the ID as six hex digits ({@code D3EEFF}). */
    public static String nameOf(int id) {
        return forId(id).map(FieldType::acronym).orElseGet(() -> hex(id));
    }

    /** The ID as six uppercase hex digits. */
    public static String hex(int id) {
        return String.format(Locale.ROOT, "%06X", id);
    }
}
