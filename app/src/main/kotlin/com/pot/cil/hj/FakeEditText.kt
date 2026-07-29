package com.pot.cil.hj

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

class FakeEditText(context: Context) : EditText(context) {

    private var textChangeListener: ((String) -> Unit)? = null

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                textChangeListener?.invoke(s?.toString() ?: "")
            }
        })
    }

    fun setOnTextChangeListener(listener: (String) -> Unit) {
        textChangeListener = listener
    }

    fun clearText() {
        setText("")
    }
}