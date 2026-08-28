package com.example.poc4

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class RegisteredFacesAdapter(
    private var persons: List<PersonRecord>,
    private val onDeleteClick: (PersonRecord) -> Unit
) : RecyclerView.Adapter<RegisteredFacesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivFaceThumbnail)
        val tvName: TextView = view.findViewById(R.id.tvPersonName)
        val tvRelationship: TextView = view.findViewById(R.id.tvPersonRelationship)
        val tvPath: TextView = view.findViewById(R.id.tvImagePath)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDeletePerson)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_registered_face, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = persons[position]
        holder.tvName.text = record.name
        holder.tvRelationship.text = if (record.relationship.isNotEmpty()) "Relacionamento: ${record.relationship}" else "Sem relacionamento informado"
        holder.tvPath.text = "Cadastrado localmente"

        try {
            val bitmap = BitmapFactory.decodeFile(record.imagePath)
            if (bitmap != null) {
                holder.ivThumbnail.setImageBitmap(bitmap)
            } else {
                holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_camera)
            }
        } catch (e: Exception) {
            holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_camera)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(record)
        }
    }

    override fun getItemCount(): Int = persons.size

    fun updateData(newPersons: List<PersonRecord>) {
        persons = newPersons
        notifyDataSetChanged()
    }
}
