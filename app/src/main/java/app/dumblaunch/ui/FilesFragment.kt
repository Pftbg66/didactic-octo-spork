package app.dumblaunch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import app.dumblaunch.MainViewModel
import app.dumblaunch.helper.KioskHelper
import androidx.fragment.app.activityViewModels
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

    private val mainViewModel: MainViewModel by activityViewModels()
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
        binding.systemFilesLink.setOnClickListener { openSystemFiles() }
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
        // Going to Android's settings must not bounce you back to the home screen afterwards
        mainViewModel.keepScreenOnReturn = true
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

    // Fallback that needs no special permission: Android's own Files app, opened at internal storage.
    private fun openSystemFiles() {
        mainViewModel.keepScreenOnReturn = true
        val root = DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary")
        val attempts = listOf(
            Intent("android.provider.action.BROWSE").setData(root),
            Intent(Intent.ACTION_VIEW).setDataAndType(root, "vnd.android.document/root")
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mainViewModel.keepScreenOnReturn = false
        Toast.makeText(requireContext(), R.string.files_cant_open, Toast.LENGTH_SHORT).show()
    }

    // Opens a tapped file with whatever app handles it. In kiosk mode that app is allowed first.
    private fun openFile(file: File) {
        val ctx = requireContext()
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val handler = ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            if (handler == null) {
                Toast.makeText(ctx, R.string.files_no_app, Toast.LENGTH_SHORT).show()
                return
            }
            KioskHelper.allowPackage(ctx, handler)
            mainViewModel.keepScreenOnReturn = true
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            mainViewModel.keepScreenOnReturn = false
            Toast.makeText(ctx, R.string.files_cant_open, Toast.LENGTH_SHORT).show()
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
            else -> openFile(row.file)
        }
    }

    private fun refresh() {
        val b = _binding ?: return
        val allowed = hasAccess()
        b.permissionMessage.visibility = if (allowed) View.GONE else View.VISIBLE
        b.systemFilesLink.visibility = if (allowed) View.GONE else View.VISIBLE
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
