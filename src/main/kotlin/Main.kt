import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ---------------------- MODELO ----------------------
sealed class TaskType {
    object Daily : TaskType()
    data class Scheduled(val date: LocalDate) : TaskType()
    data class Reminder(val date: LocalDate) : TaskType()
}

class Task(
    val title: String,
    val type: TaskType,
    completed: Boolean = false,
    var completedDate: LocalDate? = null
) {
    var check = mutableStateOf(completed)
}

// Serialização
@Serializable
data class SerializableTask(
    val title: String,
    val type: String,
    val date: String?,
    val completed: Boolean,
    val completedDate: String? = null
)

private val ISO = DateTimeFormatter.ISO_DATE
private val BR = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun Task.toSerializable(): SerializableTask {
    val typeString = when (type) {
        is TaskType.Daily -> "daily"
        is TaskType.Scheduled -> "scheduled"
        is TaskType.Reminder -> "reminder"
    }
    val dateString = when (val t = type) {
        is TaskType.Scheduled -> t.date.format(ISO)
        is TaskType.Reminder -> t.date.format(ISO)
        else -> null
    }
    val completedDateString = completedDate?.format(ISO)
    return SerializableTask(title, typeString, dateString, check.value, completedDateString)
}

fun SerializableTask.toTask(): Task {
    val taskType = when (type) {
        "daily" -> TaskType.Daily
        "scheduled" -> TaskType.Scheduled(LocalDate.parse(date!!))
        "reminder" -> TaskType.Reminder(LocalDate.parse(date!!))
        else -> TaskType.Daily
    }
    val task = Task(title, taskType, completed, completedDate?.let { LocalDate.parse(it) })
    task.check.value = completed
    return task
}

// ---------------------- ARQUIVOS ----------------------
val dataDir = File("./data").apply { if (!exists()) mkdir() }
val lastUpdateFile = File(dataDir, "last_update.txt")

@OptIn(ExperimentalSerializationApi::class)
val jsonFormatter = Json {
    prettyPrint = true
    prettyPrintIndent = "    " // usa 4 espaços (pode trocar por "\t" se preferir tab)
}

fun saveTasks(filename: String, tasks: List<Task>) {
    val file = File(dataDir, filename)
    val json = jsonFormatter.encodeToString(tasks.map { it.toSerializable() })
    file.writeText(json)
}


fun loadTasks(filename: String): List<Task> {
    val file = File(dataDir, filename)
    if (!file.exists()) return emptyList()
    val json = file.readText()
    return Json.decodeFromString<List<SerializableTask>>(json).map { it.toTask() }
}

fun readLastUpdateDate(): LocalDate? =
    if (lastUpdateFile.exists()) LocalDate.parse(lastUpdateFile.readText()) else null

fun writeLastUpdateDate(date: LocalDate) {
    lastUpdateFile.writeText(date.toString())
}

// ---------------------- COMPLETAR TAREFA (vai para histórico) ----------------------
fun handleTaskCompletion(task: Task, tasksState: MutableList<Task>) {
    if (!task.check.value) return // só processa quando marcar como concluída

    val today = LocalDate.now()
    task.completedDate = today

    when (task.type) {
        is TaskType.Scheduled -> {
            val done = loadTasks("scheduled_done.json")
            saveTasks("scheduled_done.json", done + task)

            // remove do estado e do arquivo principal
            tasksState.remove(task)
            saveTasks("scheduled.json", tasksState.filter { it.type is TaskType.Scheduled })
        }
        is TaskType.Reminder -> {
            val done = loadTasks("reminder_done.json")
            saveTasks("reminder_done.json", done + task)

            tasksState.remove(task)
            saveTasks("reminder.json", tasksState.filter { it.type is TaskType.Reminder })
        }
        else -> {
            // Diárias só persistem o estado
            saveTasks("daily.json", tasksState.filter { it.type is TaskType.Daily })
        }
    }
}

// ---------------------- UI ----------------------
@Composable
@Preview
fun App() {
    // Carregar apenas 1 vez
    val dailyInit = loadTasks("daily.json")
    val scheduledInit = loadTasks("scheduled.json").filter { !it.check.value }
    val reminderInit = loadTasks("reminder.json").filter { !it.check.value }

    val tasksState = remember {
        mutableStateListOf<Task>().apply {
            addAll(dailyInit)
            addAll(scheduledInit)
            addAll(reminderInit)
        }
    }

    // Reset diário das diárias somente se mudou o dia
    LaunchedEffect(Unit) {
        val today = LocalDate.now()
        val last = readLastUpdateDate()
        if (last == null || last != today) {
            var changed = false
            tasksState.forEach {
                if (it.type is TaskType.Daily && it.check.value) {
                    it.check.value = false
                    changed = true
                }
            }
            if (changed) saveTasks("daily.json", tasksState.filter { it.type is TaskType.Daily })
            writeLastUpdateDate(today)
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gerenciador de tarefas", style = MaterialTheme.typography.h4)
                Row {
                    Button(onClick = { showAddDialog = true }) { Text("Adicionar") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { showHistory = true }) { Text("Histórico") }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                // Diárias
                TaskColumn(
                    title = "Tarefas Diárias",
                    tasks = tasksState.filter { it.type is TaskType.Daily },
                    onTaskCheckedChange = { _ ->
                        saveTasks("daily.json", tasksState.filter { it.type is TaskType.Daily })
                    },
                    onDeleteTask = { task ->
                        // delete definitivo
                        tasksState.remove(task)
                        saveTasks("daily.json", tasksState.filter { it.type is TaskType.Daily })
                    },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    showDeleteIcon = true,   // habilitei delete
                    showRestoreIcon = false
                )

                // Agendadas
                TaskColumn(
                    title = "Tarefas Agendadas",
                    tasks = tasksState.filter { it.type is TaskType.Scheduled }
                        .sortedBy { (it.type as TaskType.Scheduled).date },
                    onTaskCheckedChange = { task -> handleTaskCompletion(task, tasksState) },
                    onDeleteTask = { task ->
                        tasksState.remove(task)
                        saveTasks("scheduled.json", tasksState.filter { it.type is TaskType.Scheduled })
                    },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    showDeleteIcon = true,
                    showRestoreIcon = false
                )

                // Lembretes
                TaskColumn(
                    title = "Outros Lembretes",
                    tasks = tasksState.filter { it.type is TaskType.Reminder }
                        .sortedBy { (it.type as TaskType.Reminder).date },
                    onTaskCheckedChange = { task -> handleTaskCompletion(task, tasksState) },
                    onDeleteTask = { task ->
                        tasksState.remove(task)
                        saveTasks("reminder.json", tasksState.filter { it.type is TaskType.Reminder })
                    },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    showDeleteIcon = true,
                    showRestoreIcon = false
                )
            }
        }

        if (showAddDialog) {
            AddTaskDialog(
                onAdd = { task ->
                    tasksState.add(task)
                    when (task.type) {
                        is TaskType.Daily -> saveTasks("daily.json", tasksState.filter { it.type is TaskType.Daily })
                        is TaskType.Scheduled -> saveTasks("scheduled.json", tasksState.filter { it.type is TaskType.Scheduled })
                        is TaskType.Reminder -> saveTasks("reminder.json", tasksState.filter { it.type is TaskType.Reminder })
                    }
                    showAddDialog = false
                },
                onDismiss = { showAddDialog = false }
            )
        }

        if (showHistory) {
            HistoryDialog(
                onDismiss = { showHistory = false },
                onRestoreScheduled = { task ->
                    // tirar do done.json
                    val done = loadTasks("scheduled_done.json").toMutableList()
                    // remove pelo "id lógico": type+title+date+completedDate
                    val ser = task.toSerializable()
                    val idx = done.indexOfFirst {
                        val s = it.toSerializable()
                        s.title == ser.title && s.type == ser.type && s.date == ser.date && s.completedDate == ser.completedDate
                    }
                    if (idx >= 0) done.removeAt(idx)
                    saveTasks("scheduled_done.json", done)

                    // restaurar para principal (sem completo)
                    task.check.value = false
                    task.completedDate = null
                    tasksState.add(task)
                    saveTasks("scheduled.json", tasksState.filter { it.type is TaskType.Scheduled })
                },
                onRestoreReminder = { task ->
                    val done = loadTasks("reminder_done.json").toMutableList()
                    val ser = task.toSerializable()
                    val idx = done.indexOfFirst {
                        val s = it.toSerializable()
                        s.title == ser.title && s.type == ser.type && s.date == ser.date && s.completedDate == ser.completedDate
                    }
                    if (idx >= 0) done.removeAt(idx)
                    saveTasks("reminder_done.json", done)

                    task.check.value = false
                    task.completedDate = null
                    tasksState.add(task)
                    saveTasks("reminder.json", tasksState.filter { it.type is TaskType.Reminder })
                }
            )
        }
    }
}

// --------- Componentes de UI reutilizáveis ---------
@Composable
fun TaskColumn(
    title: String,
    tasks: List<Task>,
    onTaskCheckedChange: (Task) -> Unit,
    modifier: Modifier = Modifier,
    onDeleteTask: ((Task) -> Unit)? = null,
    onRestoreTask: ((Task) -> Unit)? = null,
    showDeleteIcon: Boolean = false,
    showRestoreIcon: Boolean = false,
    showCheckbox: Boolean = true,
    autoScrollToEnd: Boolean = false
) {
    val listState = rememberLazyListState()

    // Se for histórico, rola até o fim quando abrir
    LaunchedEffect(autoScrollToEnd, tasks.size) {
        if (autoScrollToEnd && tasks.isNotEmpty()) {
            listState.scrollToItem(tasks.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .border(0.01.dp, Color.LightGray, RoundedCornerShape(16.dp))
            .background(Color(0xFFEFEFEF)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.h6, modifier = Modifier.padding(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            items(tasks) { task ->
                TaskRow(
                    task = task,
                    onCheckChange = onTaskCheckedChange,
                    showCheckbox = showCheckbox,
                    showDeleteIcon = showDeleteIcon && onDeleteTask != null,
                    showRestoreIcon = showRestoreIcon && onRestoreTask != null,
                    onDelete = onDeleteTask,
                    onRestore = onRestoreTask
                )
            }
        }
    }
}

@Composable
fun TaskRow(
    task: Task,
    onCheckChange: (Task) -> Unit,
    showCheckbox: Boolean,
    showDeleteIcon: Boolean,
    showRestoreIcon: Boolean,
    onDelete: ((Task) -> Unit)?,
    onRestore: ((Task) -> Unit)?
) {
    var showConfirm by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    val (suffix, isToday) = when (val t = task.type) {
        is TaskType.Scheduled -> {
            if (t.date == today) " (HOJE)" to true
            else " (${t.date.format(BR)})" to false
        }
        is TaskType.Reminder -> {
            if (t.date == today) " (HOJE)" to true
            else " (${t.date.format(BR)})" to false
        }
        else -> "" to false
    }

    SelectionContainer {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(4.dp)
        ) {
            if (showCheckbox) {
                Checkbox(
                    checked = task.check.value,
                    onCheckedChange = {
                        task.check.value = it
                        onCheckChange(task)
                    }
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                task.title + suffix,
                color = if (isToday) Color.Red else Color.Black,
                modifier = Modifier.weight(1f)
            )

            if (showDeleteIcon && onDelete != null) {
                IconButton(onClick = { showConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Deletar")
                }
            }

            if (showRestoreIcon && onRestore != null) {
                IconButton(onClick = { showConfirm = true }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Restaurar")
                }
            }
        }
    }

    if (showConfirm) {
        val isRestore = showRestoreIcon && onRestore != null
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(if (isRestore) "Restaurar Tarefa?" else "Confirmar exclusão") },
            text = {
                Text(
                    if (isRestore)
                        "Deseja restaurar esta tarefa para a lista principal?"
                    else
                        "Deseja realmente deletar esta tarefa? Essa ação não vai para o histórico."
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (isRestore) onRestore?.invoke(task) else onDelete?.invoke(task)
                    showConfirm = false
                }) { Text("Sim") }
            },
            dismissButton = {
                Button(onClick = { showConfirm = false }) { Text("Não") }
            }
        )
    }
}

@Composable
fun AddTaskDialog(onAdd: (Task) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("Diária") }
    var dateText by remember { mutableStateOf(LocalDate.now().format(BR)) }
    var typeMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar Tarefa") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tipo: ")
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(onClick = { typeMenuOpen = true }) { Text(selectedType) }
                        DropdownMenu(
                            expanded = typeMenuOpen,
                            onDismissRequest = { typeMenuOpen = false }
                        ) {
                            DropdownMenuItem(onClick = { selectedType = "Diária"; typeMenuOpen = false }) { Text("Diária") }
                            DropdownMenuItem(onClick = { selectedType = "Agendada"; typeMenuOpen = false }) { Text("Agendada") }
                            DropdownMenuItem(onClick = { selectedType = "Lembrete"; typeMenuOpen = false }) { Text("Lembrete") }
                        }
                    }
                }
                if (selectedType != "Diária") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("Data (dd/MM/yyyy)") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    val task = when (selectedType) {
                        "Diária" -> Task(title, TaskType.Daily)
                        "Agendada" -> Task(title, TaskType.Scheduled(LocalDate.parse(dateText, BR)))
                        else -> Task(title, TaskType.Reminder(LocalDate.parse(dateText, BR)))
                    }
                    onAdd(task)
                }
            }) { Text("Adicionar") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun HistoryDialog(
    onDismiss: () -> Unit,
    onRestoreScheduled: (Task) -> Unit,
    onRestoreReminder: (Task) -> Unit
) {
    val scheduledHistory = remember { mutableStateListOf<Task>().apply { addAll(loadTasks("scheduled_done.json")) } }
    val reminderHistory  = remember { mutableStateListOf<Task>().apply { addAll(loadTasks("reminder_done.json")) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Histórico") },
        text = {
            Row(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                TaskColumn(
                    title = "Agendadas Concluídas",
                    tasks = scheduledHistory.sortedBy {
                        // ordenar por data da tarefa; se não houver, deixa por título
                        when (val t = it.type) {
                            is TaskType.Scheduled -> t.date
                            else -> LocalDate.MIN
                        }
                    },
                    onTaskCheckedChange = { },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    onRestoreTask = { task ->
                        // atualiza UI local imediatamente
                        scheduledHistory.remove(task)
                        onRestoreScheduled(task)
                    },
                    showDeleteIcon = false,
                    showRestoreIcon = true,
                    showCheckbox = false,
                    autoScrollToEnd = true
                )
                TaskColumn(
                    title = "Lembretes Concluídos",
                    tasks = reminderHistory.sortedBy {
                        when (val t = it.type) {
                            is TaskType.Reminder -> t.date
                            else -> LocalDate.MIN
                        }
                    },
                    onTaskCheckedChange = { },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    onRestoreTask = { task ->
                        reminderHistory.remove(task)
                        onRestoreReminder(task)
                    },
                    showDeleteIcon = false,
                    showRestoreIcon = true,
                    showCheckbox = false,
                    autoScrollToEnd = true
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Fechar") } }
    )
}

// ---------------------- MAIN ----------------------
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "My Tasks") {
        MaterialTheme { App() }
    }
}
