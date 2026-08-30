package org.futo.inputmethod.latin.uix.actions.aipolish

/** Where the text shown in the AI Polish window came from; decides what "Replace" replaces. */
enum class SourceKind { Selection, WholeField, None }

data class Source(val text: String, val kind: SourceKind)

/** Prefers the selection, then the whole field; blank strings count as absent. */
fun pickSource(selected: String?, all: String?): Source = when {
    !selected.isNullOrBlank() -> Source(selected, SourceKind.Selection)
    !all.isNullOrBlank() -> Source(all, SourceKind.WholeField)
    else -> Source("", SourceKind.None)
}
