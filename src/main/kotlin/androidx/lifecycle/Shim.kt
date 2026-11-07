package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.MainScope
import java.util.Collections
import java.util.WeakHashMap

open class AndroidViewModel(app: Application)

private val scopes = Collections.synchronizedMap(WeakHashMap<AndroidViewModel, CoroutineScope>())
val AndroidViewModel.viewModelScope: CoroutineScope
    get() = scopes.getOrPut(this) { MainScope() }
