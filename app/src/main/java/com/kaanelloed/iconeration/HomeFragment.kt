package com.kaanelloed.iconeration

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kaanelloed.iconeration.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val act = requireActivity() as MainActivity

        binding.buttonFirst.setOnClickListener {
            act.currentPack = null
            findNavController().navigate(R.id.action_HomeFragment_to_IconFragment)
        }

        Thread {
            if (act.packs == null)
                act.packs = ApplicationManager(activity?.packageManager!!).getIconPackApps()

            view.post {
                val packAdapter = PackListAdapter(act.packs!!)
                packAdapter.onItemClick = {
                    act.currentPack = it.packageName
                    findNavController().navigate(R.id.action_HomeFragment_to_IconFragment)
                }
                view.findViewById<RecyclerView>(R.id.packView).apply {
                    layoutManager = LinearLayoutManager(view.context)
                    adapter = packAdapter
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        //val adapter = binding.root.findViewById<RecyclerView>(R.id.packView).adapter as PackListAdapter
        //adapter.onItemClick = null

        super.onDestroyView()
        _binding = null
    }

    class PackListAdapter(private val dataSet: Array<PackageInfoStruct>): RecyclerView.Adapter<PackListAdapter.ViewHolder>() {
        var onItemClick: ((PackageInfoStruct) -> Unit)? = null

        inner class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
            val appIcon: ImageView = view.findViewById(R.id.appIcon)
            val appName: TextView = view.findViewById(R.id.appName)

            init {
                // Define click listener for the ViewHolder's View
                itemView.setOnClickListener {
                    onItemClick?.invoke(dataSet[adapterPosition])
                }
            }
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            // Create a new view, which defines the UI of the list item
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.pack_info_item, viewGroup, false)

            return ViewHolder(view)
        }

        // Replace the contents of a view (invoked by the layout manager)
        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

            // Get element from your dataset at this position and replace the
            // contents of the view with that element
            val app = dataSet[position]

            viewHolder.appIcon.setImageDrawable(app.icon)
            viewHolder.appName.text = app.appName
        }

        // Return the size of your dataset (invoked by the layout manager)
        override fun getItemCount() = dataSet.size
    }
}