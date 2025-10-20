package ru.fourpda.tickets

data class TicketData(
    val id: String,
    val title: String,
    val section: String,
    val date: String,
    val status: Int, // 0 = новый, 1 = в работе, 2 = обработан
    val moderator: String = "", // Ответственный модератор
    val topic: String = "", // Тема тикета
    val sender: String = "", // Кто отправил тикет
    val postAuthor: String = "" // Автор поста
) {
    fun getStatusText(): String {
        return when (status) {
            0 -> "Новый"
            1 -> "В работе"
            2 -> "Обработан"
            else -> "Неизвестно"
        }
    }
    
    fun getStatusColor(): Int {
        return when (status) {
            0 -> android.R.color.holo_red_dark      // Красный для новых
            1 -> android.R.color.holo_orange_dark   // Оранжевый для в работе
            2 -> android.R.color.holo_green_dark    // Зеленый для обработанных
            else -> android.R.color.darker_gray
        }
    }
}
