# Catalogue fonts

The bundled Jost and Cormorant Garamond fonts are licensed under the adjacent
SIL Open Font License files. The Cormorant binaries retain the complete glyph
set but omit OpenType ligature substitutions. This keeps words such as
`Confirm` and `gifting` searchable and copyable in generated PDFs while the
printed typography remains visually unchanged.

Every Jost and Cormorant binary also has its Greek code points removed from the
cmap (Δ Ω μ π, the only Greek these fonts carried, as maths symbols; the glyphs
stay in the file, unmapped). The PDF renderer takes each character from the first
face that maps it, so a Greek word used to draw those four letters from Jost and
the rest from DejaVu Sans: Ω read as a small ω. Now DejaVu Sans draws every Greek
letter while Latin text and figures keep their face. Run
`python3 scripts/catalog-fonts-without-greek.py src/main/resources/fonts/catalog/*.ttf`
again after replacing a font file (it leaves a stripped font unchanged).
