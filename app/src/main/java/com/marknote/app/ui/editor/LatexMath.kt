package com.marknote.app.ui.editor

internal val inlineMathPattern = Regex("""\$([^\s$](?:[^\n$]*[^\s$])?)\$(?![\d$])""")

internal val doubleDollarMathPattern = Regex("""\$\$([\s\S]+?)\$\$""")

internal val mathSegmentPattern = Regex(
    doubleDollarMathPattern.pattern + "|" + inlineMathPattern.pattern,
)
