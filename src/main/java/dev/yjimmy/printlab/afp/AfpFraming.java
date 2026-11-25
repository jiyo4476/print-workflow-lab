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
     * As {@link #STRICT}, but carriage returns and line feeds are skipped before each field (so
     * between fields, and before the first), and zero bytes are skipped at the very end of the
     * file: files that went through a text transfer, or were padded to a block size. Zero bytes
     * anywhere else are not skipped, because a field whose length is too short leaves them behind;
     * a file that is nothing but zeros has no fields and fails like any other file with none.
     * This is looser than {@link #STRICT} only in those bytes: each field is still checked.
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
     * Picks a framing from the first bytes of the file. A file that starts like a field (0x5A, a
     * length, then 0xD3) is {@link #PADDED} if line endings come before it or right after its first
     * field, and {@link #STRICT} otherwise, so a strict file is still read strictly; one that starts
     * with a record or block descriptor word and then a 0x5A is {@link #RDW} or {@link #BDW}. Any
     * other file fails with a description of what it looks like. A file padded with zero bytes but
     * no line endings needs {@code padded} to be asked for.
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
