package `in`.bleuphoenix.flutterpreview.core

/** What the variant matrix varies. Each dimension adds its variants next to the preview as declared. */
enum class VariantDimension(val label: String) {
    THEME("Light / dark theme"),
    TEXT_SCALE("Larger text (1.5×, 2×)"),
    DIRECTION("Right-to-left"),
    DEVICE_SIZE("Small phone and tablet widths"),
}

/**
 * One variation of a preview. Null fields keep the preview's own value; [device] replaces the width, and the
 * height too when the preview is page-sized (see the harness).
 */
data class PreviewVariant(
    val id: String,
    val label: String,
    /** Dart expression, e.g. `fp_ui.Brightness.dark`. */
    val brightness: String? = null,
    val textScale: Double? = null,
    val rtl: Boolean = false,
    /** Logical width × height of the device. */
    val device: Pair<Double, Double>? = null,
)

object PreviewVariants {

    const val BASE_ID = "base"
    private const val SEPARATOR = '\u0001'

    val DEFAULT_DIMENSIONS: Set<VariantDimension> = VariantDimension.entries.toSet()

    /** The preview as declared, followed by one variant per value of each dimension. */
    fun forEntry(entry: PreviewEntry, dimensions: Set<VariantDimension>): List<PreviewVariant> = buildList {
        add(PreviewVariant(BASE_ID, "As declared"))
        if (VariantDimension.THEME in dimensions) {
            val dark = entry.brightness?.contains("dark") == true
            add(
                if (dark) PreviewVariant("light", "Light", brightness = "fp_ui.Brightness.light")
                else PreviewVariant("dark", "Dark", brightness = "fp_ui.Brightness.dark"),
            )
        }
        if (VariantDimension.TEXT_SCALE in dimensions) {
            for (scale in listOf(1.5, 2.0)) {
                if (entry.textScaleFactor != scale) add(PreviewVariant("text$scale", "Text ${format(scale)}×", textScale = scale))
            }
        }
        if (VariantDimension.DIRECTION in dimensions) add(PreviewVariant("rtl", "Right-to-left", rtl = true))
        if (VariantDimension.DEVICE_SIZE in dimensions) {
            add(PreviewVariant("small", "Small phone (320 wide)", device = 320.0 to 568.0))
            add(PreviewVariant("tablet", "Tablet (800 wide)", device = 800.0 to 1280.0))
        }
    }

    /** The key a variant's image is stored under in [BatchResult.images]. */
    fun imageKey(entryKey: String, variantId: String) = "$entryKey$SEPARATOR$variantId"

    private fun format(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

/** Render [variants] of [entry] in the same run as the file's previews. */
data class VariantRequest(val entry: PreviewEntry, val variants: List<PreviewVariant>)
