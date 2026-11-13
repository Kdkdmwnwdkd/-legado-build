package com.shimmer.reader.ui.dict.rule

import android.app.Application
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.DictRule
import com.shimmer.reader.help.DefaultData
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.R

class DictRuleViewModel(application: Application) : BaseViewModel(application) {


    fun update(vararg dictRule: DictRule) {
        execute {
            appDb.dictRuleDao.update(*dictRule)
        }.onError {
            val msg = context.getString(R.string.dict_rule_update_failed, it.localizedMessage)
            AppLog.put(msg, it)
            context.toastOnUi(msg)
        }
    }

    fun delete(vararg dictRule: DictRule) {
        execute {
            appDb.dictRuleDao.delete(*dictRule)
        }.onError {
            val msg = context.getString(R.string.dict_rule_delete_failed, it.localizedMessage)
            AppLog.put(msg, it)
            context.toastOnUi(msg)
        }
    }

    fun upSortNumber(rules: List<DictRule>) {
        if (rules.isEmpty()) return
        val normalized = rules.mapIndexed { index, rule ->
            rule.copy(sortNumber = index + 1)
        }
        execute {
            appDb.dictRuleDao.update(*normalized.toTypedArray())
        }
    }

    fun enableSelection(vararg dictRule: DictRule) {
        execute {
            val array = dictRule.map { it.copy(enabled = true) }.toTypedArray()
            appDb.dictRuleDao.insert(*array)
        }
    }

    fun disableSelection(vararg dictRule: DictRule) {
        execute {
            val array = dictRule.map { it.copy(enabled = false) }.toTypedArray()
            appDb.dictRuleDao.insert(*array)
        }
    }

    fun importDefault() {
        execute {
            DefaultData.importDefaultDictRules()
        }
    }

}
