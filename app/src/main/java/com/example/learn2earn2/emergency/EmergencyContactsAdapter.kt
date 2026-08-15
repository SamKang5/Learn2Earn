package com.example.learn2earn2.emergency

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.learn2earn2.R

class EmergencyContactsAdapter(
    private val onEdit: (EmergencyContact) -> Unit,
    private val onRemove: (EmergencyContact) -> Unit,
    private val onMoveUp: (EmergencyContact) -> Unit,
    private val onMoveDown: (EmergencyContact) -> Unit
) : RecyclerView.Adapter<EmergencyContactsAdapter.ViewHolder>() {

    private var contacts = emptyList<EmergencyContact>()

    fun submitContacts(nextContacts: List<EmergencyContact>) {
        contacts = nextContacts
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_emergency_contact_name)
        val phone: TextView = view.findViewById(R.id.tv_emergency_contact_phone)
        val moveUp: Button = view.findViewById(R.id.btn_move_contact_up)
        val moveDown: Button = view.findViewById(R.id.btn_move_contact_down)
        val edit: ImageButton = view.findViewById(R.id.btn_edit_contact)
        val remove: ImageButton = view.findViewById(R.id.btn_remove_contact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_emergency_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.name.text = contact.displayName
        holder.phone.text = EmergencyPhoneNumbers.mask(contact.phoneNumber)
        holder.moveUp.isEnabled = position > 0
        holder.moveDown.isEnabled = position < contacts.lastIndex
        holder.moveUp.alpha = if (holder.moveUp.isEnabled) 1f else 0.4f
        holder.moveDown.alpha = if (holder.moveDown.isEnabled) 1f else 0.4f
        holder.edit.setOnClickListener { onEdit(contact) }
        holder.remove.setOnClickListener { onRemove(contact) }
        holder.moveUp.setOnClickListener { onMoveUp(contact) }
        holder.moveDown.setOnClickListener { onMoveDown(contact) }
    }

    override fun getItemCount(): Int = contacts.size
}
