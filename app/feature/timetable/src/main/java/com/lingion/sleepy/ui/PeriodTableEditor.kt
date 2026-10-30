/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Sleepy contributors, frozen 3dafa110f74ac4f32464ce1efddb24197a4091d9:
 * ui/component/TimeSlotEditor.kt:514-600 (node/start/end rows and draft callbacks),
 * ui/component/DateTimePickers.kt:132-195 (Material3 TimePicker field/dialog).
 * Android's existing 24-hour TimePickerDialog supplies the actual time input.
 * Uses host theme and existing Period repository; no independent database/VM.
 */
package com.lingion.sleepy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeriodTableEditor(snapshot:TimetableSnapshot,enabled:Boolean,saving:Boolean,error:String?,
    onSave:(SemesterSettings,List<Period>)->Unit,onClose:()->Unit) {
    BackHandler(enabled=!saving,onBack=onClose)
    val rows=rememberSaveable(snapshot.periods,saver=periodFormsSaver) {
        mutableStateListOf<PeriodForm>().apply {addAll(snapshot.periods.map(::PeriodForm))}
    }
    val existingDurations=snapshot.periods.map {ChronoUnit.MINUTES.between(it.start,it.end).toInt()}
    var uniform by rememberSaveable {mutableStateOf(existingDurations.isNotEmpty() && existingDurations.distinct().size==1)}
    var duration by rememberSaveable {mutableStateOf((existingDurations.firstOrNull() ?: 45).toString())}
    var localError by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingFingerprint by rememberSaveable {mutableStateOf<String?>(null)}
    var pickerIndex by rememberSaveable {mutableIntStateOf(-1)}
    var pickerStart by rememberSaveable {mutableStateOf(true)}
    fun clock(raw:String)=LocalTime.parse(raw.trim())
    fun printed(t:LocalTime)=t.format(DateTimeFormatter.ofPattern("HH:mm"))
    fun minutes()=duration.toIntOrNull()?.takeIf {it in 1..1439} ?: throw IllegalArgumentException("请填写有效课时时长。")
    fun displayEnd(row:PeriodForm)=if(uniform) printed(PeriodEditorTimes.endAt(clock(row.start),minutes())) else row.end
    fun fingerprint(periods:List<Period>)=periods.joinToString("|") {"${it.number}:${it.start}:${it.end}"}
    LaunchedEffect(snapshot.periods,saving,pendingFingerprint,error) {
        if(pendingFingerprint!=null && !saving && error==null && fingerprint(snapshot.periods)==pendingFingerprint) onClose()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onClose,enabled=!saving) {Text("返回")}
            Text("编辑时间表",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            TextButton(onClick={
                try {
                    val candidate=rows.map {row ->
                        val number=row.number.toIntOrNull() ?: throw IllegalArgumentException("节次编号无效。")
                        Period(number,clock(row.start),clock(displayEnd(row)))
                    }
                    val problems=TimetableRules.validatePeriods(candidate)
                    require(problems.isEmpty()) {problems.joinToString("\n")}
                    localError=null
                    if(candidate==snapshot.periods) onClose() else {
                        pendingFingerprint=fingerprint(candidate);onSave(snapshot.settings,candidate)
                    }
                } catch(failure:Exception) {localError=failure.message ?: "请检查时间设置。"}
            },enabled=enabled && !saving) {Text(if(saving) "保存中…" else "保存")}
        }
        localError?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=16.dp))}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=16.dp))}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("每节课时长相同",modifier=Modifier.weight(1f))
                            Switch(checked=uniform,onCheckedChange={next ->
                                if(!next && uniform) runCatching {rows.map(::displayEnd)}.onSuccess {ends -> rows.forEachIndexed {i,row ->row.end=ends[i]}}
                                uniform=next;localError=null
                            },enabled=!saving,modifier=Modifier.semantics {contentDescription="每节课时长相同"})
                        }
                        OutlinedTextField(value=duration,onValueChange={if(it.length<=4 && it.all(Char::isDigit)) {duration=it;localError=null}},
                            label={Text("一节课时长")},suffix={Text("分钟")},singleLine=true,enabled=uniform && !saving,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                            modifier=Modifier.fillMaxWidth().semantics {contentDescription="统一课时分钟"})
                    }
                }
            }
            item {Text("请注意是24小时制！",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyMedium)}
            itemsIndexed(rows,key={_,row->row.number}) {index,row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("第 ${row.number} 节",modifier=Modifier.weight(1f))
                        OutlinedButton(onClick={pickerIndex=index;pickerStart=true},enabled=!saving,
                            contentPadding=PaddingValues(horizontal=10.dp,vertical=8.dp),modifier=Modifier.width(84.dp).semantics {contentDescription="作息${row.number}开始"}) {Text(row.start)}
                        Text("–")
                        OutlinedButton(onClick={pickerIndex=index;pickerStart=false},enabled=!saving && !uniform,
                            contentPadding=PaddingValues(horizontal=10.dp,vertical=8.dp),modifier=Modifier.width(84.dp).semantics {contentDescription="作息${row.number}结束"}) {Text(runCatching {displayEnd(row)}.getOrDefault("待调整"))}
                        TextButton(onClick={rows.remove(row)},enabled=!saving,modifier=Modifier.width(28.dp),contentPadding=PaddingValues(0.dp)) {Text("−")}
                    }
                }
            }
            item {
                OutlinedButton(onClick={
                    val number=(rows.mapNotNull {it.number.toIntOrNull()}.maxOrNull() ?: 0)+1
                    val start=rows.lastOrNull()?.let {runCatching {clock(displayEnd(it)).plusMinutes(10)}.getOrNull()} ?: LocalTime.of(8,0)
                    val end=runCatching {PeriodEditorTimes.endAt(start,duration.toIntOrNull() ?: 45)}.getOrDefault(start)
                    rows+=PeriodForm(null).apply {this.number=number.toString();this.start=printed(start);this.end=printed(end)}
                },enabled=!saving) {Text("新增节次")}
            }
            item {Text("调整只在保存后生效；不会修改课程中的明确自定义时间。",style=MaterialTheme.typography.bodySmall)}
        }
    }
    if(pickerIndex in rows.indices) key(pickerIndex,pickerStart) {
        val row=rows[pickerIndex]
        val initial=runCatching {clock(if(pickerStart) row.start else row.end)}.getOrDefault(LocalTime.of(8,0))
        val context=LocalContext.current
        DisposableEffect(row,pickerStart) {
            val dialog=android.app.TimePickerDialog(context,{_,hour,minute ->
                val value=printed(LocalTime.of(hour,minute))
                if(pickerStart) row.start=value else row.end=value
                pickerIndex=-1;localError=null
            },initial.hour,initial.minute,true)
            dialog.setTitle("选择时间")
            dialog.setOnDismissListener {pickerIndex=-1}
            dialog.show()
            onDispose {dialog.setOnDismissListener(null);dialog.dismiss()}
        }
    }
}
