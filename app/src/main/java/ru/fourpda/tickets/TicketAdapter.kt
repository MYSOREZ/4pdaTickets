package ru.fourpda.tickets

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton

class TicketAdapter(
    private val onTicketClick: (TicketData) -> Unit
) : RecyclerView.Adapter<TicketAdapter.TicketViewHolder>() {

    private var tickets: List<TicketData> = emptyList()
    private val expandedPositions = mutableSetOf<Int>()

    fun setTickets(newTickets: List<TicketData>) {
        tickets = newTickets
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TicketViewHolder {
        return try {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ticket, parent, false)
            TicketViewHolder(view)
        } catch (e: Exception) {
            android.util.Log.e("TicketAdapter", "Ошибка создания ViewHolder: ${e.message}")
            throw e // Пробрасываем исключение дальше, так как это критическая ошибка
        }
    }

    override fun onBindViewHolder(holder: TicketViewHolder, position: Int) {
        holder.bind(tickets[position])
    }

    override fun getItemCount() = tickets.size

    inner class TicketViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView as MaterialCardView
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        private val textDate: TextView = itemView.findViewById(R.id.textDate)
        private val textTitle: TextView = itemView.findViewById(R.id.textTitle)
        private val textSection: TextView = itemView.findViewById(R.id.textSection)
        private val textSender: TextView = itemView.findViewById(R.id.textSender)
        private val textTicketId: TextView = itemView.findViewById(R.id.textTicketId)
        
        // Дополнительные поля для расширенного содержимого
        private val expandedContent: View = itemView.findViewById(R.id.expandedContent)
        private val textTopic: TextView = itemView.findViewById(R.id.textTopic)
        private val textModerator: TextView = itemView.findViewById(R.id.textModerator)
        private val textPostAuthor: TextView = itemView.findViewById(R.id.textPostAuthor)
        private val btnOpenTicket: MaterialButton = itemView.findViewById(R.id.btnOpenTicket)

        fun bind(ticket: TicketData) {
            val position = adapterPosition
            val isExpanded = expandedPositions.contains(position)
            
            // Установка основных данных
            textStatus.text = ticket.getStatusText()
            textDate.text = DateUtils.formatTicketDate(ticket.date)
            textTitle.text = ticket.title
            textSection.text = ticket.section
            textTicketId.text = "#${ticket.id}"
            
            // Отображаем раздел
            textSection.text = ticket.section
            
            // Отображаем отправителя, если он есть
            if (ticket.sender.isNotEmpty()) {
                textSender.text = "Отправитель: ${ticket.sender}"
                textSender.visibility = View.VISIBLE
            } else {
                textSender.visibility = View.GONE
            }

            // Цвет индикатора статуса
            statusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(itemView.context, ticket.getStatusColor())
            )
            
            // Установка данных для расширенного содержимого
            textTopic.text = if (ticket.topic.isNotEmpty()) ticket.topic else "Не указана"
            textModerator.text = if (ticket.moderator.isNotEmpty()) ticket.moderator else "Не назначен"
            textPostAuthor.text = if (ticket.postAuthor.isNotEmpty()) ticket.postAuthor else "Неизвестен"
            
            // Управляем видимостью расширенного содержимого
            expandedContent.visibility = if (isExpanded) View.VISIBLE else View.GONE

            // Обработчик клика для расширения/сворачивания
            cardView.setOnClickListener {
                if (isExpanded) {
                    expandedPositions.remove(position)
                } else {
                    expandedPositions.add(position)
                }
                notifyItemChanged(position)
                
                // Также вызываем обратный вызов для возможных дополнительных действий
                onTicketClick(ticket)
            }

            // Обработчик для кнопки "Перейти к тикету"
            btnOpenTicket.setOnClickListener {
                val context = itemView.context
                val ticketUrl = "https://4pda.to/forum/index.php?act=ticket\u0026s=thread\u0026t_id=${ticket.id}"
                val intent = IntentDebugger.createRobustFourpdaIntent(context, ticketUrl)
                context.startActivity(intent)
            }
        }
    }
}
