package com.example.customkeyboard

object KeyboardLayoutData {

    val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    val englishRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    // Standard Arabic phone keyboard layout (mapped to Latin QWERTY key positions)
    val arabicRows = listOf(
        listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
        listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
        listOf("ذ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "د")
    )

    val symbolsRows = listOf(
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("*", "\"", "'", ":", ";", "!", "?", "%"),
        listOf("~", "`", "|", "•", "√", "π", "÷", "×")
    )

    val emojiRows = listOf(
        listOf("😀", "😂", "😍", "😎", "🤔", "😭", "😅", "🙏", "👍", "🔥"),
        listOf("❤️", "🎉", "✨", "😴", "🤯", "😉", "🙌", "👀", "💯", "🤝"),
        listOf("🌟", "🎯", "🪄", "🎩", "🃏", "✅", "❌", "⭐")
    )
}
