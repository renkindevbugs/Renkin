package com.kaanelloed.iconeration

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.kaanelloed.iconeration.databinding.FragmentIconPackBinding

class IconPackFragment : Fragment() {

    private var loaded = false
    private var _binding: FragmentIconPackBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loaded)
                    findNavController().popBackStack()
                else
                    Snackbar.make(
                        binding.root,
                        "Please wait until the process is complete",
                        Snackbar.LENGTH_LONG
                    )
                        .setAnchorView(R.id.nav_host_fragment_content_main)
                        .setAction("Wait", null).show()
            }
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIconPackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Thread {
            val act = requireActivity() as MainActivity

            requireView().post {
                requireActivity().window.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }

            val creator = IconPackCreator(view.context, act.apps!!)
            creator.create(this::addText)

            requireView().post {
                view.findViewById<ProgressBar>(R.id.progressBar).visibility = View.INVISIBLE
                requireActivity().window.clearFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }

            loaded = true
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun addText(text: String) {
        val txt = requireView().findViewById<TextView>(R.id.progress_text)
        requireView().post {
            txt.text = txt.text.toString() + text + "\n"
        }
    }
}