import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.serialization.*
import kotlinx.serialization.json.*
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

fun Task.toSerializable(): SerializableTask {
    val typeString = when (type) {
        is TaskType.Daily -> "daily"
        is TaskType.Scheduled -> "scheduled"
        is TaskType.Reminder -> "reminder"
    }
    val dateString = when (type) {
        is TaskType.Scheduled -> (type as TaskType.Scheduled).date.format(DateTimeFormatter.ISO_DATE)
        is TaskType.Reminder -> (type as TaskType.Reminder).date.format(DateTimeFormatter.ISO_DATE)
        else -> null
    }
    val completedDateString = completedDate?.format(DateTimeFormatter.ISO_DATE)
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

fun saveTasks(filename: String, tasks: List<Task>) {
    val file = File(dataDir, filename)
    val json = Json.encodeToString(tasks.map { it.toSerializable() })
    file.writeText(json)
}

fun loadTasks(filename: String): List<Task> {
    val file = File(dataDir, filename)
    if (!file.exists()) return emptyList()
    val json = file.readText()
    return Json.decodeFromString<List<SerializableTask>>(json).map { it.toTask() }
}

fun readLastUpdateDate(): LocalDate? {
    return if (lastUpdateFile.exists()) LocalDate.parse(lastUpdateFile.readText()) else null
}

fun writeLastUpdateDate(date: LocalDate) {
    lastUpdateFile.writeText(date.toString())
}

// ---------------------- FUNÇÃO DE COMPLETAR TAREFA ----------------------
fun handleTaskCompletion(task: Task, tasks: MutableList<Task>) {
    if (!task.check.value) return

    val today = LocalDate.now()
    task.completedDate = today
    task.check.value = true

    when (task.type) {
        is TaskType.Scheduled -> {
            // Carregar histórico
            val doneFile = File(dataDir, "scheduled_done.json")
            val doneTasks = if (doneFile.exists()) Json.decodeFromString<List<SerializableTask>>(doneFile.readText()).map { it.toTask() } else emptyList()
            saveTasks("scheduled_done.json", doneTasks + task)

            // Atualizar arquivo principal removendo a tarefa concluída
            val remaining = tasks.filter { it != task }
            saveTasks("scheduled.json", remaining.filter { it.type is TaskType.Scheduled })
            tasks.remove(task)
        }

        is TaskType.Reminder -> {
            val doneFile = File(dataDir, "reminder_done.json")
            val doneTasks = if (doneFile.exists()) Json.decodeFromString<List<SerializableTask>>(doneFile.readText()).map { it.toTask() } else emptyList()
            saveTasks("reminder_done.json", doneTasks + task)

            val remaining = tasks.filter { it != task }
            saveTasks("reminder.json", remaining.filter { it.type is TaskType.Reminder })
            tasks.remove(task)
        }

        else -> {
            // Diárias continuam normalmente
            saveTasks("daily.json", tasks.filter { it.type is TaskType.Daily })
        }
    }
}


// ---------------------- UI ----------------------
@Composable
@Preview
fun App() {
    val tasksState = remember { mutableStateListOf<Task>().apply {
        addAll(loadTasks("daily.json"))
        addAll(loadTasks("scheduled.json").filter { !it.check.value })
        addAll(loadTasks("reminder.json").filter { !it.check.value })
    } }

    // Reset diário de tarefas diárias se for outro dia
    LaunchedEffect(Unit) {
        val today = LocalDate.now()
        val lastUpdate = readLastUpdateDate()
        if (lastUpdate == null || lastUpdate != today) {
            tasksState.forEach { if (it.type is TaskType.Daily) it.check.value = false }
            writeLastUpdateDate(today)
            saveTasks("daily.json", tasksState.filter { it.type is TaskType.Daily })
        }
    }

    var showDialog by remember { mutableStateOf(false) }

    fun saveAllTasks() {
        saveTasks("daily.json", tasksState.filter { it.type is TaskType.Daily })
        saveTasks("scheduled.json", tasksState.filter { it.type is TaskType.Scheduled })
        saveTasks("reminder.json", tasksState.filter { it.type is TaskType.Reminder })
    }

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Gerenciador de Tarefas", style = MaterialTheme.typography.h5)
                Button(onClick = { showDialog = true }) { Text("Adicionar") }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                TaskColumn(
                    title = "Tarefas Agendadas",
                    tasks = tasksState.filter { it.type is TaskType.Scheduled }.sortedBy { (it.type as TaskType.Scheduled).date },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    onTaskCheckedChange = { task -> handleTaskCompletion(task, tasksState) },
                    onDeleteTask = { task ->
                        tasksState.remove(task)
                        saveTasks("scheduled.json", tasksState.filter { it.type is TaskType.Scheduled })
                    }
                )
                TaskColumn(
                    title = "Tarefas Diárias",
                    tasks = tasksState.filter { it.type is TaskType.Daily },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    onTaskCheckedChange = { saveAllTasks() },
                    onDeleteTask = {}
                )
                TaskColumn(
                    title = "Outros Lembretes",
                    tasks = tasksState.filter { it.type is TaskType.Reminder }.sortedBy { (it.type as TaskType.Reminder).date },
                    modifier = Modifier.weight(1f).padding(8.dp),
                    onTaskCheckedChange = { task -> handleTaskCompletion(task, tasksState) },
                    onDeleteTask = { task ->
                        tasksState.remove(task)
                        saveTasks("reminder.json", tasksState.filter { it.type is TaskType.Reminder })
                    }
                )
            }
        }

        if (showDialog) {
            AddTaskDialog(
                onAdd = { task ->
                    tasksState.add(task)
                    saveAllTasks()
                    showDialog = false
                },
                onDismiss = { showDialog = false }
            )
        }
    }
}

@Composable
fun TaskColumn(
    title: String,
    tasks: List<Task>,
    onTaskCheckedChange: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(Color(0xFFEFEFEF)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.h6, modifier = Modifier.padding(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tasks) { task ->
                TaskRow(
                    task = task,
                    onCheckChange = onTaskCheckedChange,
                    onDelete = onDeleteTask
                )
            }
        }
    }
}

@Composable
fun TaskRow(task: Task, onCheckChange: (Task) -> Unit, onDelete: (Task) -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val displayText = task.title + when (task.type) {
        is TaskType.Scheduled -> {
            if ((task.type as TaskType.Scheduled).date == today) " (HOJE)"
            else " (${(task.type as TaskType.Scheduled).date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))})"
        }
        is TaskType.Reminder -> {
            if ((task.type as TaskType.Reminder).date == today) " (HOJE)"
            else " (${(task.type as TaskType.Reminder).date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))})"
        }
        else -> ""
    }
    val isToday = (task.type is TaskType.Scheduled && (task.type as TaskType.Scheduled).date == today) ||
            (task.type is TaskType.Reminder && (task.type as TaskType.Reminder).date == today)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(4.dp)
    ) {
        Checkbox(
            checked = task.check.value,
            onCheckedChange = {
                task.check.value = it
                onCheckChange(task)
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            displayText,
            color = if (isToday) Color.Red else Color.Black,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (task.type is TaskType.Scheduled || task.type is TaskType.Reminder) {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Deletar")
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Confirmar exclusão") },
            text = { Text("Deseja realmente deletar esta tarefa?") },
            confirmButton = {
                Button(onClick = {
                    onDelete(task)
                    showDeleteDialog = false
                }) { Text("Sim") }
            },
            dismissButton = {
                Button(onClick = { showDeleteDialog = false }) { Text("Não") }
            }
        )
    }
}


@Composable
fun AddTaskDialog(onAdd: (Task) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("Diária") }
    var dateText by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar Tarefa") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tipo: ")
                    Spacer(modifier = Modifier.width(8.dp))
                    DropdownMenuDemo(selectedType) { selectedType = it }
                }
                if (selectedType != "Diária") {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("Data (dd/MM/yyyy)") }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    val task = when (selectedType) {
                        "Diária" -> Task(title, TaskType.Daily)
                        "Agendada" -> Task(title, TaskType.Scheduled(LocalDate.parse(dateText, DateTimeFormatter.ofPattern("dd/MM/yyyy"))))
                        else -> Task(title, TaskType.Reminder(LocalDate.parse(dateText, DateTimeFormatter.ofPattern("dd/MM/yyyy"))))
                    }
                    onAdd(task)
                }
            }) { Text("Adicionar") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}


@Composable
fun DropdownMenuDemo(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(onClick = { onSelect("Diária"); expanded = false }) { Text("Diária") }
            DropdownMenuItem(onClick = { onSelect("Agendada"); expanded = false }) { Text("Agendada") }
            DropdownMenuItem(onClick = { onSelect("Lembrete"); expanded = false }) { Text("Lembrete") }
        }
    }
}

// ---------------------- MAIN ----------------------
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Task Manager Compose") {
        App()
    }
}
