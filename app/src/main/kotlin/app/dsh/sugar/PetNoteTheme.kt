package app.dsh.sugar

import android.content.Context
import android.graphics.drawable.Drawable

/** Ink, paper and small printed symbols for one answer sheet. */
internal class PetNoteTheme private constructor(
    val pack: String,
    val text: Int,
    val ink: Int,
    val accent: Int,
    val mark: Int,
    val clasp: Int?,
    val mic: Int,
    val confirm: Int,
) {
    fun paper(context: Context): Drawable = if (clasp == null) PetAnswerPaperDrawable(context)
        else PetPackPaperDrawable(context, pack)

    fun wash(alpha: Int): Int = (ink and 0x00ffffff) or (alpha shl 24)

    companion object {
        private val sugar = PetNoteTheme("sugar", 0xff30364c.toInt(), 0xff34467d.toInt(), 0xffa67d3d.toInt(),
            R.drawable.note_sugar_mark, R.drawable.note_sugar_clip, R.drawable.note_sugar_mic, R.drawable.note_sugar_confirm)
        private val themes = listOf(
            sugar,
            PetNoteTheme("paopao01_tea", 0xff304952.toInt(), 0xff355f79.toInt(), 0xffb67f70.toInt(),
                R.drawable.note_paopao01_tea_mark, R.drawable.note_paopao01_tea_clip, R.drawable.note_paopao01_tea_mic, R.drawable.note_paopao01_tea_confirm),
            PetNoteTheme("paopao02_winter", 0xff374956.toInt(), 0xff435977.toInt(), 0xff5191a1.toInt(),
                R.drawable.note_paopao02_winter_mark, R.drawable.note_paopao02_winter_clip, R.drawable.note_paopao02_winter_mic, R.drawable.note_paopao02_winter_confirm),
            PetNoteTheme("paopao03_sleep", 0xff413849.toInt(), 0xff533f68.toInt(), 0xffb79660.toInt(),
                R.drawable.pet_answer_mark, null, R.drawable.pet_answer_mic, R.drawable.pet_answer_confirm),
            PetNoteTheme("paopao04_starlit", 0xfff8f1df.toInt(), 0xffffdf9a.toInt(), 0xff98c2f4.toInt(),
                R.drawable.note_paopao04_starlit_mark, R.drawable.note_paopao04_starlit_clip, R.drawable.note_paopao04_starlit_mic, R.drawable.note_paopao04_starlit_confirm),
            PetNoteTheme("paopao05_sapphire", 0xff303e59.toInt(), 0xff395688.toInt(), 0xff558ba7.toInt(),
                R.drawable.note_paopao05_sapphire_mark, R.drawable.note_paopao05_sapphire_clip, R.drawable.note_paopao05_sapphire_mic, R.drawable.note_paopao05_sapphire_confirm),
            PetNoteTheme("paopao06_sailor", 0xff2e4856.toInt(), 0xff32596f.toInt(), 0xffaa7840.toInt(),
                R.drawable.note_paopao06_sailor_mark, R.drawable.note_paopao06_sailor_clip, R.drawable.note_paopao06_sailor_mic, R.drawable.note_paopao06_sailor_confirm),
            PetNoteTheme("paopao07_cream", 0xff414956.toInt(), 0xff435e75.toInt(), 0xffaa8064.toInt(),
                R.drawable.note_paopao07_cream_mark, R.drawable.note_paopao07_cream_clip, R.drawable.note_paopao07_cream_mic, R.drawable.note_paopao07_cream_confirm),
            PetNoteTheme("paopao09_rain", 0xff344452.toInt(), 0xff344f76.toInt(), 0xff3f91a0.toInt(),
                R.drawable.note_paopao09_rain_mark, R.drawable.note_paopao09_rain_clip, R.drawable.note_paopao09_rain_mic, R.drawable.note_paopao09_rain_confirm),
            PetNoteTheme("paopao10_press", 0xff3d3a34.toInt(), 0xff414e60.toInt(), 0xffa65946.toInt(),
                R.drawable.note_paopao10_press_mark, R.drawable.note_paopao10_press_clip, R.drawable.note_paopao10_press_mic, R.drawable.note_paopao10_press_confirm),
        ).associateBy { it.pack }

        fun forPack(id: String): PetNoteTheme = themes[id] ?: sugar
    }
}
