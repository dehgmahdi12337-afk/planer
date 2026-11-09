package ir.dehgmahdi.planner

import android.content.Context

object Store {
    fun p(c: Context) = c.getSharedPreferences("planner", Context.MODE_PRIVATE)
}
