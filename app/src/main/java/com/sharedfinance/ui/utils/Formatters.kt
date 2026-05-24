package com.sharedfinance.ui.utils

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Double.asCurrency(): String {
    val formatter = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
    return "${formatter.format(this)} RUB"
}

fun Date.asShortDateTime(): String {
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(this)
}

fun Date.asShortDate(): String {
    return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(this)
}
