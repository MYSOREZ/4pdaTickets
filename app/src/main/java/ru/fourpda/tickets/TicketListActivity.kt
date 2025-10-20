package ru.fourpda.tickets

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Временный минимальный экран списка тикетов.
 * Полноценная реализация будет возвращена позже; этот скелет нужен,
 * чтобы восстановить сборку после случайной порчи файла.
 */
class TicketListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ticket_list)
        // TODO: восстановить логику списка тикетов и адаптер
    }
}
