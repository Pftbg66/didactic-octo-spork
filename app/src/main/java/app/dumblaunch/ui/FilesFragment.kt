package app.dumblaunch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.dumblaunch.R
import app.dumblaunch.databinding.FragmentFilesBinding
import app.dumblaunch.databinding.ItemFileBinding
import java.io.File

/** A tiny read-only file browser for the phone's local storage. */
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private val storageRoot: File = Environment.getExternalStorageDirectory()
    private var current: File = storageRoot
    private val adapter = FileRowAdapter { onRowClick(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.permissionMessage.setOnClickListener { requestAccess() }
        binding.pathText.setOnClickListener { goUp() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!goUp()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + requireContext().packageName)
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    private fun goUp(): Boolean {
        if (current == storageRoot) return false
        current = current.parentFile ?: storageRoot
        refresh()
        return true
    }

    private fun onRowClick(row: Row) {
        when {
            row.file == null -> goUp()
            row.file.isDirectory -> {
                current = row.file
                refresh()
            }
        }
    }

    private fun refresh() {
        val b = _binding ?: return
        val allowed = hasAccess()
        b.permissionMessage.visibility = if (allowed) View.GONE else View.VISIBLE
        b.list.visibility = if (allowed) View.VISIBLE else View.GONE
        b.pathText.visibility = if (allowed) View.VISIBLE else View.GONE
        b.storageText.visibility = if (allowed) View.VISIBLE else View.GONE
        if (!allowed) {
            b.emptyText.visibility = View.GONE
            return
        }

        if (!current.exists()) current = storageRoot
        b.pathText.text = if (current == storageRoot) "/" else "/" + current.relativeTo(storageRoot).path

        try {
            val stat = StatFs(storageRoot.path)
            b.storageText.text = getString(
                R.string.files_storage,
                Formatter.formatShortFileSize(requireContext(), stat.availableBytes),
                Formatter.formatShortFileSize(requireContext(), stat.totalBytes)
            )
        } catch (e: Exception) {
            b.storageText.text = ""
        }

        val children = (current.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        val rows = mutableListOf<Row>()
        if (current != storageRoot) rows.add(Row(null, "..", ""))
        children.forEach {
            rows.add(
                if (it.isDirectory) Row(it, it.name + "/", "")
                else Row(it, it.name, Formatter.formatShortFileSize(requireContext(), it.length()))
            )
        }
        adapter.submit(rows)
        b.emptyText.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
    }

    private data class Row(val file: File?, val label: String, val size: String)

    private class FileRowAdapter(private val onClick: (Row) -> Unit) :
        RecyclerView.Adapter<FileRowAdapter.Holder>() {

        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        class Holder(val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.b.fileName.text = row.label
            holder.b.fileSize.text = row.size
            holder.b.root.setOnClickListener { onClick(row) }
        }
    }
}
