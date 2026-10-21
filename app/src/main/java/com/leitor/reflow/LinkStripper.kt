package com.leitor.reflow

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** Destino de um link: página interna (page >= 0) ou endereço externo (uri). */
class LinkTarget(val page: Int, val uri: String?)

/**
 * Área de um link na página, em pontos, com origem no canto superior esquerdo
 * da área visível da página. [id] é o índice na lista de LinkTarget.
 */
class PageLink(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val id: Int)

/** Extrai o texto e marca as palavras que estão sobre um link. */
class LinkStripper : PDFTextStripper() {

    var pageLinks: List<PageLink> = emptyList()

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val id = findLink(textPositions)
        if (id >= 0) {
            super.writeString("${Reflow.OPEN}$id${Reflow.MID}$text${Reflow.END}", textPositions)
        } else {
            super.writeString(text, textPositions)
        }
    }

    private fun findLink(positions: List<TextPosition>): Int {
        if (pageLinks.isEmpty()) return -1
        for (p in positions) {
            val cx = p.getXDirAdj() + p.getWidthDirAdj() / 2f
            val cy = p.getYDirAdj() - p.getHeightDir() * 0.3f
            for (l in pageLinks) {
                if (cx >= l.x0 - 2f && cx <= l.x1 + 2f && cy >= l.y0 - 2f && cy <= l.y1 + 2f) {
                    return l.id
                }
            }
        }
        return -1
    }
}

/** Lê os links da página. Os destinos novos são acrescentados em [targets]. */
fun extractLinks(doc: PDDocument, page: PDPage, targets: MutableList<LinkTarget>): List<PageLink> {
    val out = ArrayList<PageLink>()
    if (page.getRotation() != 0) return out // páginas giradas: links não suportados
    val crop = page.getCropBox()
    for (a in page.getAnnotations()) {
        if (a !is PDAnnotationLink) continue
        val r = a.getRectangle() ?: continue
        val t = resolveTarget(doc, a) ?: continue
        val id = targets.size
        targets.add(t)
        val x0 = r.getLowerLeftX() - crop.getLowerLeftX()
        val x1 = r.getUpperRightX() - crop.getLowerLeftX()
        val top = crop.getHeight() - (r.getUpperRightY() - crop.getLowerLeftY())
        val bottom = crop.getHeight() - (r.getLowerLeftY() - crop.getLowerLeftY())
        out.add(PageLink(minOf(x0, x1), minOf(top, bottom), maxOf(x0, x1), maxOf(top, bottom), id))
    }
    return out
}

private fun resolveTarget(doc: PDDocument, link: PDAnnotationLink): LinkTarget? {
    try {
        var dest: PDDestination? = link.getDestination()
        if (dest == null) {
            val act = link.getAction()
            if (act is PDActionURI) {
                val u = act.getURI()
                return if (u.isNullOrBlank()) null else LinkTarget(-1, u)
            }
            if (act is PDActionGoTo) dest = act.getDestination()
        }
        if (dest is PDNamedDestination) {
            dest = doc.getDocumentCatalog().findNamedDestinationPage(dest)
        }
        if (dest is PDPageDestination) {
            val n = dest.retrievePageNumber()
            if (n >= 0) return LinkTarget(n, null)
        }
    } catch (e: Exception) {
    }
    return null
}
