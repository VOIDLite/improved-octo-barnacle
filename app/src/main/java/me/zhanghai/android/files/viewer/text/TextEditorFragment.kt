/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorFragmentBinding
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels
import java.nio.charset.Charset
import java.util.regex.Pattern

class TextEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path

    private lateinit var binding: TextEditorFragmentBinding

    private lateinit var menuBinding: MenuBinding

    private val viewModel by viewModels { { TextEditorViewModel(argsFile) } }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var isSettingText = false

    private var searchPattern: Pattern? = null
    private val searchMatchIndices = mutableListOf<Int>()
    private var currentSearchMatchIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        lifecycleScope.launchWhenStarted {
            onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    ConfirmCloseDialogFragment.show(this@TextEditorFragment)
                }
            }
            launch {
                viewModel.isTextChanged.collect {
                    onBackPressedCallback.isEnabled = viewModel.isTextChanged.value
                }
            }
            addOnBackPressedCallback(onBackPressedCallback)

            launch { viewModel.encoding.collect { onEncodingChanged(it) } }
            launch { viewModel.textState.collect { onTextStateChanged(it) } }
            launch { viewModel.isTextChanged.collect { onIsTextChangedChanged(it) } }
            launch { viewModel.writeFileState.collect { onWriteFileStateChanged(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        TextEditorFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argsFile = args.intent.extraPath
        if (argsFile == null) {
            // TODO: Show a toast.
            finish()
            return
        }
        this.argsFile = argsFile

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }

        ThemedFastScroller.create(binding.scrollView)
        binding.textEdit.setEnableLineNumber(true)
        val context = requireContext()
        val secondaryTextColor = context.getColorByAttr(android.R.attr.textColorSecondary)
        binding.textEdit.setLineNumberTextColor(secondaryTextColor)
        binding.textEdit.setLineNumberTextSize(binding.textEdit.textSize * 0.8f)

        val keywordsPattern = Pattern.compile(
            "\\b(class|interface|fun|val|var|import|package|public|private|protected|internal|return|if|else|for|while|do|break|continue|switch|case|default|try|catch|finally|throw|new|this|super|null|true|false|void|int|float|double|long|short|byte|char|boolean|string|def|elif|from|as|in|is|and|or|not|lambda|with|assert|pass|yield|async|await)\\b"
        )
        binding.textEdit.addSyntaxPattern(keywordsPattern, Color.parseColor("#8E24AA"))
        val numbersPattern = Pattern.compile("\\b\\d+(\\.\\d+)?\\b")
        binding.textEdit.addSyntaxPattern(numbersPattern, Color.parseColor("#00ACC1"))
        val stringsPattern = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        binding.textEdit.addSyntaxPattern(stringsPattern, Color.parseColor("#43A047"))
        val commentsPattern = Pattern.compile("//.*|/\\*[\\s\\S]*?\\*/|#.*")
        binding.textEdit.addSyntaxPattern(commentsPattern, Color.parseColor("#9E9E9E"))

        binding.textEdit.enablePairComplete(true)
        val pairMap = mapOf(
            '{' to '}',
            '(' to ')',
            '[' to ']',
            '"' to '"',
            '\'' to '\''
        )
        binding.textEdit.setPairCompleteMap(pairMap)

        binding.textEdit.setEnableAutoIndentation(true)
        binding.textEdit.setTabLength(4)
        binding.textEdit.setIndentationStarts(setOf('{'))
        binding.textEdit.setIndentationEnds(setOf('}'))

        binding.findEdit.doAfterTextChanged {
            performSearch(it?.toString().orEmpty())
        }
        binding.findPrevButton.setOnClickListener {
            navigateToMatch(currentSearchMatchIndex - 1)
        }
        binding.findNextButton.setOnClickListener {
            navigateToMatch(currentSearchMatchIndex + 1)
        }
        binding.closeFindButton.setOnClickListener {
            hideFindReplaceBar()
        }
        binding.replaceButton.setOnClickListener {
            performReplace()
        }
        binding.replaceAllButton.setOnClickListener {
            performReplaceAll()
        }

        // Manually save and restore state in view model to avoid TransactionTooLargeException.
        binding.textEdit.isSaveEnabled = false
        val textEditSavedState = viewModel.removeEditTextSavedState()
        if (textEditSavedState != null) {
            binding.textEdit.onRestoreInstanceState(textEditSavedState)
        }
        binding.textEdit.doAfterTextChanged {
            if (isSettingText) {
                return@doAfterTextChanged
            }
            // Might happen if the animation is running and user is quick enough.
            if (viewModel.textState.value !is DataState.Success) {
                return@doAfterTextChanged
            }
            viewModel.isTextChanged.value = true
        }

        // TODO: Request storage permission if not granted.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        viewModel.setEditTextSavedState(binding.textEdit.onSaveInstanceState())
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateSaveMenuItem()
        updateEncodingMenuItems()
        if (this::menuBinding.isInitialized) {
            menuBinding.monospaceItem.isChecked = binding.textEdit.typeface == android.graphics.Typeface.MONOSPACE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_save -> {
                save()
                true
            }
            R.id.action_find -> {
                toggleFindReplaceBar()
                true
            }
            R.id.action_monospace -> {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    binding.textEdit.typeface = android.graphics.Typeface.MONOSPACE
                } else {
                    binding.textEdit.typeface = android.graphics.Typeface.DEFAULT
                }
                true
            }
            R.id.action_reload -> {
                onReload()
                true
            }
            Menu.FIRST -> {
                viewModel.encoding.value = Charset.forName(item.titleCondensed!!.toString())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    fun onSupportNavigateUp(): Boolean {
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    override fun finish() {
        requireActivity().finish()
    }

    private fun onEncodingChanged(encoding: Charset) {
        updateEncodingMenuItems()
    }

    private fun updateEncodingMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val charsetName = viewModel.encoding.value.name()
        val charsetItem = menuBinding.encodingSubMenu.children
            .find { it.titleCondensed == charsetName }!!
        charsetItem.isChecked = true
    }

    private fun onTextStateChanged(state: DataState<String>) {
        updateTitle()
        when (state) {
            is DataState.Loading -> {
                binding.progress.fadeInUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeOutUnsafe()
            }
            is DataState.Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeInUnsafe()
                if (!viewModel.isTextChanged.value) {
                    setText(state.data)
                }
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeInUnsafe()
                binding.errorText.text = state.throwable.toString()
                binding.textEdit.fadeOutUnsafe()
            }
        }
    }

    private fun setText(text: String?) {
        isSettingText = true
        binding.textEdit.setText(text)
        isSettingText = false
        viewModel.isTextChanged.value = false
    }

    private fun onIsTextChangedChanged(changed: Boolean) {
        updateTitle()
    }

    private fun updateTitle() {
        val fileName = viewModel.file.value.fileName.toString()
        val changed = viewModel.isTextChanged.value
        requireActivity().title = getString(
            if (changed) {
                R.string.text_editor_title_changed_format
            } else {
                R.string.text_editor_title_format
            }, fileName
        )
    }

    private fun onReload() {
        if (viewModel.isTextChanged.value) {
            ConfirmReloadDialogFragment.show(this)
        } else {
            reload()
        }
    }

    override fun reload() {
        viewModel.isTextChanged.value = false
        viewModel.reload()
    }

    private fun save() {
        val text = binding.textEdit.text.toString()
        viewModel.writeFile(argsFile, text, requireContext())
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> updateSaveMenuItem()
            is ActionState.Success -> {
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
                viewModel.isTextChanged.value = false
            }
            // The error will be toasted by service so we should never show it in UI.
            is ActionState.Error -> viewModel.finishWritingFile()
        }
    }

    private fun updateSaveMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.saveItem.isEnabled = viewModel.writeFileState.value.isReady
    }

    private fun performSearch(query: String) {
        searchPattern?.let {
            binding.textEdit.removeSyntaxPattern(it)
        }
        searchMatchIndices.clear()
        currentSearchMatchIndex = -1
        searchPattern = null

        if (query.isNotEmpty()) {
            try {
                val pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE)
                searchPattern = pattern
                binding.textEdit.addSyntaxPattern(pattern, Color.parseColor("#FFD54F"))
                
                val text = binding.textEdit.text.toString()
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    searchMatchIndices.add(matcher.start())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        binding.textEdit.reHighlightSyntax()
        updateSearchUi()
    }

    private fun updateSearchUi() {
        if (searchMatchIndices.isEmpty()) {
            binding.matchCountText.text = "0/0"
            binding.findNextButton.isEnabled = false
            binding.findPrevButton.isEnabled = false
        } else {
            if (currentSearchMatchIndex == -1) {
                currentSearchMatchIndex = 0
            }
            binding.matchCountText.text = "${currentSearchMatchIndex + 1}/${searchMatchIndices.size}"
            binding.findNextButton.isEnabled = true
            binding.findPrevButton.isEnabled = true
        }
    }

    private fun navigateToMatch(index: Int) {
        if (searchMatchIndices.isEmpty()) return
        val size = searchMatchIndices.size
        currentSearchMatchIndex = (index + size) % size
        val start = searchMatchIndices[currentSearchMatchIndex]
        val query = binding.findEdit.text.toString()
        val end = start + query.length
        binding.textEdit.requestFocus()
        binding.textEdit.setSelection(start, end)
        updateSearchUi()
    }

    private fun performReplace() {
        val query = binding.findEdit.text.toString()
        val replacement = binding.replaceEdit.text.toString()
        if (query.isEmpty() || currentSearchMatchIndex == -1) return
        val text = binding.textEdit.text.toString()
        val start = searchMatchIndices[currentSearchMatchIndex]
        val end = start + query.length
        if (text.substring(start, end).equals(query, ignoreCase = true)) {
            val newText = text.substring(0, start) + replacement + text.substring(end)
            isSettingText = true
            binding.textEdit.setText(newText)
            isSettingText = false
            viewModel.isTextChanged.value = true
            performSearch(query)
            if (searchMatchIndices.isNotEmpty()) {
                navigateToMatch(currentSearchMatchIndex)
            }
        }
    }

    private fun performReplaceAll() {
        val query = binding.findEdit.text.toString()
        val replacement = binding.replaceEdit.text.toString()
        if (query.isEmpty()) return
        val text = binding.textEdit.text.toString()
        val pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE)
        val newText = pattern.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement))
        if (newText != text) {
            isSettingText = true
            binding.textEdit.setText(newText)
            isSettingText = false
            viewModel.isTextChanged.value = true
            performSearch(query)
        }
    }

    private fun toggleFindReplaceBar() {
        if (binding.findReplaceBar.visibility == View.VISIBLE) {
            hideFindReplaceBar()
        } else {
            showFindReplaceBar()
        }
    }

    private fun showFindReplaceBar() {
        binding.findReplaceBar.visibility = View.VISIBLE
        binding.findEdit.requestFocus()
        val query = binding.findEdit.text.toString()
        if (query.isNotEmpty()) {
            performSearch(query)
        }
    }

    private fun hideFindReplaceBar() {
        binding.findReplaceBar.visibility = View.GONE
        searchPattern?.let {
            binding.textEdit.removeSyntaxPattern(it)
            binding.textEdit.reHighlightSyntax()
        }
        searchPattern = null
        searchMatchIndices.clear()
        currentSearchMatchIndex = -1
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class MenuBinding private constructor(
        val menu: Menu,
        val saveItem: MenuItem,
        val encodingSubMenu: SubMenu,
        val monospaceItem: MenuItem,
        val findItem: MenuItem
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.text_editor, menu)
                val encodingSubMenu = menu.findItem(R.id.action_encoding).subMenu!!
                for ((charsetName, charset) in Charset.availableCharsets()) {
                    encodingSubMenu.add(Menu.NONE, Menu.FIRST, Menu.NONE, charset.displayName())
                        .titleCondensed = charsetName
                }
                encodingSubMenu.setGroupCheckable(Menu.NONE, true, true)
                return MenuBinding(
                    menu,
                    menu.findItem(R.id.action_save),
                    encodingSubMenu,
                    menu.findItem(R.id.action_monospace),
                    menu.findItem(R.id.action_find)
                )
            }
        }
    }
}
