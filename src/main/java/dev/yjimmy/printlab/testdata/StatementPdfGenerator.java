package dev.yjimmy.printlab.testdata;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import dev.yjimmy.printlab.testdata.StatementLayout.TextLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Generates a fake "statement print run": many customer statements concatenated into one PDF,
 * like the output of a billing system. Every value sits at a fixed position so the workflow can
 * find it. The statements come from {@link StatementDataGenerator}, so
 * {@link StatementAfpGenerator} writes the same ones as AFP for the same seed.
 * {@link StatementLayout} says where each piece of text goes.
 *
 * <p>The file is saved the way PDFBox saves by default, as most current tools do: a
 * cross-reference stream and object streams (PDF 1.5). {@link #document} gives the document
 * unsaved, for a test that saves it another way.
 */
public final class StatementPdfGenerator {

    /** Passwords for {@link Defects#encrypt()} files; nothing here needs them to be secret. */
    static final String OWNER_PASSWORD = "owner";
    static final String USER_PASSWORD = "user";

    /**
     * Deliberate print-readiness defects for preflight tests (PRD-11 FR-8). Every flag is off in
     * {@link #NONE}, which is the default output and unchanged by these options.
     *
     * @param a4 pages are A4 instead of Letter (a page size the Letter-only check fails)
     * @param encrypt the file is protected with a user password, so it fails the encryption check
     * @param formFont each page's text is drawn inside a Form XObject that uses a non-embedded
     *     Helvetica, so the font check must look into forms to find it
     */
    public record Defects(boolean a4, boolean encrypt, boolean formFont) {

        public static final Defects NONE = new Defects(false, false, false);

        public boolean any() {
            return a4 || encrypt || formFont;
        }
    }

    private final StatementDataGenerator data;

    public StatementPdfGenerator(long seed) {
        this.data = new StatementDataGenerator(seed);
    }

    public void generate(int statements, Path out) throws IOException {
        generate(statements, 0, out);
    }

    /**
     * @param badAddresses how many statements get a blank name or a 4-digit zip (alternately, in
     *     statement order), at most {@code statements}
     */
    public void generate(int statements, int badAddresses, Path out) throws IOException {
        generate(statements, badAddresses, false, out);
    }

    /** As {@link #generate(int, int, Path)}; with {@code dpart}, the file also gets a DPart tree. */
    public void generate(int statements, int badAddresses, boolean dpart, Path out)
            throws IOException {
        generate(statements, badAddresses, dpart, Defects.NONE, out);
    }

    /** As {@link #generate(int, int, boolean, Path)}, with the given deliberate {@code defects}. */
    public void generate(int statements, int badAddresses, boolean dpart, Defects defects, Path out)
            throws IOException {
        write(data.generate(statements, badAddresses), out, dpart, defects);
    }

    /** Writes {@code statements} as one PDF. */
    public static void write(List<Statement> statements, Path out) throws IOException {
        write(statements, out, false);
    }

    /** Writes {@code statements} as one PDF, with a DPart tree if {@code dpart}. */
    public static void write(List<Statement> statements, Path out, boolean dpart)
            throws IOException {
        write(statements, out, dpart, Defects.NONE);
    }

    /** As {@link #write(List, Path, boolean)}, with the given deliberate {@code defects}. */
    public static void write(List<Statement> statements, Path out, boolean dpart, Defects defects)
            throws IOException {
        try (PDDocument doc = document(statements, dpart, defects)) {
            if (defects.encrypt()) {
                doc.protect(new StandardProtectionPolicy(OWNER_PASSWORD, USER_PASSWORD,
                        new AccessPermission()));
            }
            doc.save(out.toFile());
        }
    }

    /** The run of {@code statements} for this seed, not yet saved; the caller closes it. */
    public PDDocument document(int statements) throws IOException {
        return document(data.generate(statements, 0));
    }

    /** {@code statements} as one document, not yet saved; the caller closes it. */
    public static PDDocument document(List<Statement> statements) throws IOException {
        return document(statements, false);
    }

    /**
     * {@code statements} as one document, not yet saved; the caller closes it. With {@code dpart},
     * the catalog also gets a {@code /DPartRoot}: one DPart per statement, from its first page to
     * its last, with its account number, name and zip as {@code /DPM} entries.
     */
    public static PDDocument document(List<Statement> statements, boolean dpart) throws IOException {
        return document(statements, dpart, Defects.NONE);
    }

    /** As {@link #document(List, boolean)}, with the given deliberate {@code defects}. */
    public static PDDocument document(List<Statement> statements, boolean dpart, Defects defects)
            throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDRectangle pageSize = defects.a4() ? PDRectangle.A4 : PDRectangle.LETTER;
        PDDocument doc = new PDDocument();
        try {
            List<List<PDPage>> pagesByStatement = new ArrayList<>();
            for (Statement statement : statements) {
                int pages = statement.pageCount();
                List<PDPage> own = new ArrayList<>();
                for (int p = 1; p <= pages; p++) {
                    PDPage page = new PDPage(pageSize);
                    doc.addPage(page);
                    own.add(page);
                    List<TextLine> lines = StatementLayout.lines(statement, p);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        if (defects.formFont()) {
                            drawInForm(doc, cs, pageSize, font, bold, lines);
                        } else {
                            for (TextLine line : lines) {
                                text(cs, line.bold() ? bold : font, line.size(), line.x(), line.y(),
                                        line.text(), pageSize.getHeight());
                            }
                        }
                    }
                }
                pagesByStatement.add(own);
            }
            if (dpart) {
                addDPartTree(doc, statements, pagesByStatement);
            }
            return doc;
        } catch (IOException | RuntimeException e) {
            doc.close();
            throw e;
        }
    }

    /**
     * Draws the page's text in a Form XObject and places the form on the page: the text is the
     * same, but a font check has to walk into the form to see the font.
     */
    private static void drawInForm(PDDocument doc, PDPageContentStream cs, PDRectangle pageSize,
            PDType1Font font, PDType1Font bold, List<TextLine> lines) throws IOException {
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(pageSize);
        PDResources resources = new PDResources();
        COSName regular = resources.add(font);
        COSName emphasis = resources.add(bold);
        form.setResources(resources);
        StringBuilder ops = new StringBuilder();
        for (TextLine line : lines) {
            ops.append(String.format(Locale.ROOT, "BT /%s %.2f Tf 1 0 0 1 %.2f %.2f Tm (%s) Tj ET%n",
                    (line.bold() ? emphasis : regular).getName(), (double) line.size(),
                    (double) line.x(), (double) (pageSize.getHeight() - line.y()),
                    escape(line.text())));
        }
        try (OutputStream out = form.getContentStream().createOutputStream()) {
            out.write(ops.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        cs.drawForm(form);
    }

    /** Escapes the characters that end or start a PDF literal string. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    /**
     * Adds the {@code /DPartRoot}: {@code /DParts} holds one node per statement, with {@code /Start}
     * and {@code /End} pointing at its first and last page objects and a {@code /DPM} dictionary,
     * and every page of the statement gets a {@code /DPart} entry pointing back at its node. The
     * tree has the PDF 2.0 form but the file isn't PDF/VT conformant (no output intents or XMP).
     */
    private static void addDPartTree(PDDocument doc, List<Statement> statements,
            List<List<PDPage>> pagesByStatement) {
        COSName parentKey = COSName.getPDFName("Parent");
        // /Parent makes the tree circular, so the root, its node and every statement's node are
        // indirect objects: a COSObject wrapper is written once and referenced everywhere else.
        COSDictionary root = new COSDictionary();
        COSObject rootRef = new COSObject(root);
        COSDictionary rootNode = new COSDictionary();
        COSObject rootNodeRef = new COSObject(rootNode);

        COSArray siblings = new COSArray();
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            List<PDPage> pages = pagesByStatement.get(i);
            COSDictionary dpm = new COSDictionary();
            dpm.setString("AccountNumber", statement.accountNumber());
            dpm.setString("Name", statement.name());
            dpm.setString("Zip", statement.zip());
            COSDictionary node = new COSDictionary();
            node.setName(COSName.TYPE, "DPart");
            node.setItem(parentKey, rootNodeRef);
            node.setItem(COSName.getPDFName("Start"), pages.get(0).getCOSObject());
            node.setItem(COSName.getPDFName("End"), pages.get(pages.size() - 1).getCOSObject());
            node.setItem(COSName.getPDFName("DPM"), dpm);
            COSObject nodeRef = new COSObject(node);
            siblings.add(nodeRef);
            // As PDF/VT does, every page points back at its node (through which the whole tree can
            // be reached), so a splitter that copies pages as they are copies the tree with them.
            for (PDPage page : pages) {
                page.getCOSObject().setItem(COSName.getPDFName("DPart"), nodeRef);
            }
        }
        // The PDF 2.0 form: /DParts is an array of arrays; here, one array holding every statement.
        COSArray groups = new COSArray();
        groups.add(siblings);
        rootNode.setName(COSName.TYPE, "DPart");
        rootNode.setItem(parentKey, rootRef);
        rootNode.setItem(COSName.getPDFName("DParts"), groups);

        COSArray levelNames = new COSArray();
        levelNames.add(COSName.getPDFName("root"));
        levelNames.add(COSName.getPDFName("record"));
        root.setName(COSName.TYPE, "DPartRoot");
        root.setItem(COSName.getPDFName("DPartRootNode"), rootNodeRef);
        root.setItem(COSName.getPDFName("RecordLevel"), COSInteger.get(1));
        root.setItem(COSName.getPDFName("NodeNameList"), levelNames);
        doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("DPartRoot"), rootRef);
    }

    /** Draws text with its baseline at {@code yFromTop} points below the top of the page. */
    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x,
            float yFromTop, String value, float pageHeight) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, pageHeight - yFromTop);
        cs.showText(value);
        cs.endText();
    }
}
