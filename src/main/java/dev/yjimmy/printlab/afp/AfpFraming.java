package dev.yjimmy.printlab.afp;

import java.util.Locale;

/**
 * How the structured fields of an AFP file are laid end to end. MO:DCA defines the fields, not the
 * file around them, and the tools that write AFP disagree. Every form below has each field start
 * with {@code 0x5A}, as {@link AfpReader} expects; they differ in what sits between fields.
 */
public enum AfpFraming {

    /** {@code 5A field 5A field ...}: nothing between fields. What this project writes. */
    STRICT,

    /**
     * As {@link #STRICT}, but carriage returns, line feeds and zero bytes between fields (and
     * after the last one) are skipped: files that went through a text transfer, or were padded to
     * a block size.
     */
    PADDED,

    /**
     * A 4-byte record descriptor word before each field: the record's length, counting the word
     * itself, then two zero bytes. What a variable-length ("V" or "VB") mainframe data set has
     * after a binary transfer, with the block descriptor words removed.
     */
    RDW,

    /**
     * As {@link #RDW}, with each block of records also led by a 4-byte block descriptor word (the
     * block's length, counting the word itself, then two zero bytes): a variable blocked ("VB")
     * data set as it was on the mainframe. Records that span blocks (VBS) aren't supported.
     */
    BDW,

    /**
     * Picks {@link #PADDED}, {@link #RDW} or {@link #BDW} from the first bytes of the file, and
     * fails with a description of the file if none fits.
     */
    AUTO;

    /** The framing called {@code name} in a workflow, in any case; fails if there's none. */
    public static AfpFraming named(String name) {
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown framing '" + name
                    + "'; use strict, padded, rdw, bdw or auto", e);
        }
    }
}
