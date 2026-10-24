package com.speechnotes.app.logic

/** One image extracted from an office document, ready for the normalized EPUB. */
data class DocumentImage(
    val data: ByteArray,
    val mime: String,
    val alt: String = "",
) {
    override fun equals(other: Any?): Boolean =
        other is DocumentImage && other.data.contentEquals(data) && other.mime == mime && other.alt == alt
    override fun hashCode(): Int = (31 * data.contentHashCode() + mime.hashCode()) * 31 + alt.hashCode()
}

/** One cell of a parsed office table. [columnSpan] keeps merged columns aligned. */
data class DocumentTableCell(
    val text: String,
    val columnSpan: Int = 1,
    val isHeader: Boolean = false,
)

/** One content block of a document chapter. */
sealed class DocumentBlock {
    data class Paragraph(val text: String) : DocumentBlock()
    /** Index into the parse result's image pool. */
    data class Image(val index: Int) : DocumentBlock()
    data class Table(val rows: List<List<DocumentTableCell>>) : DocumentBlock()
    /** Format-native markup written into the chapter verbatim (after token substitution). */
    data class Html(val markup: String) : DocumentBlock()
}

/** One chapter of a parsed document — the common currency of the pipeline. */
data class DocumentChapter(
    val title: String?,
    val blocks: List<DocumentBlock>,
) {
    /** The historical paragraph-only view of a chapter. */
    val paragraphs: List<String>
        get() = blocks.mapNotNull { it as? DocumentBlock.Paragraph }
            .map { it.text }
}

/** Everything one parser reduces a format to. */
data class DocumentParseResult(
    val chapters: List<DocumentChapter>,
    val images: List<DocumentImage> = emptyList(),
    val cover: DocumentImage? = null,
)

/** Parser failures — user-presentable strings ride Book.importError. */
sealed class DocumentParseError(message: String) : Exception(message) {
    class MissingEntry(name: String) : DocumentParseError("The file is missing $name — it may be a renamed file of a different kind.")
    class Malformed(why: String) : DocumentParseError("The document is malformed: $why")
}

/**
 * One dispatch table for the normalize-to-EPUB formats: everything that is
 * not EPUB/PDF/audio but becomes a book through [DocumentEpubConverter].
 * Ported from DocumentBook.swift (speechnotes-ios).
 */
object DocumentBook {

    /** Every extension this pipeline normalizes to EPUB, lowercase, no dot. */
    val supportedExtensions: Set<String> = setOf(
        // Office
        "docx", "doc", "odt", "pptx", "odp",
        // Mobipocket / Kindle
        "mobi", "azw", "azw3", "prc", "pdb",
        // FictionBook
        "fb2",
        // RTF
        "rtf",
        // Web
        "html", "htm", "htmlz",
        // Plain text / Markdown
        "txt", "text", "md", "markdown",
    )

    fun canNormalize(fileExtension: String): Boolean =
        supportedExtensions.contains(fileExtension.lowercase())

    /**
     * The formats this port implements as PARSERS. Mobi/fb2/rtf/html/txt
     * already have dedicated Android readers elsewhere in `logic/` (the
     * shelf imports them through their own paths), so they are absent here.
     */
    val parsedExtensions: Set<String> = setOf("docx", "odt", "pptx", "odp")

    fun canParse(fileExtension: String): Boolean =
        parsedExtensions.contains(fileExtension.lowercase())

    /**
     * Parses [data] as the document [fileExtension] names it. Throws the
     * parser's own error on a malformed file. Run OFF the main thread.
     */
    fun parse(fileExtension: String, data: ByteArray): DocumentParseResult =
        when (fileExtension.lowercase()) {
            "docx" -> DocxParser.parseFull(data)
            "odt" -> OdtParser.parseFull(data)
            "pptx" -> PptxParser.parseFull(data)
            "odp" -> OdpParser.parseFull(data)
            else -> throw DocumentParseError.Malformed("'$fileExtension' is not a document this reader supports")
        }
}
