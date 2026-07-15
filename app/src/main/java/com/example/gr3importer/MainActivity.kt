package com.example.gr3importer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

data class ImageItem(
    val documentFile: DocumentFile,
    val isImported: Boolean,
    val lastModified: Long, // 毎回SDにアクセスしなくて済むように日付をキャッシュ
    val isSelected: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var rootUri by remember { mutableStateOf<Uri?>(null) }
    var imageList by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0) }
    var totalToImport by remember { mutableStateOf(0) }

    // 日付（月ごと）にグループ化したリストを自動計算
    val groupedItems by remember(imageList) {
        derivedStateOf {
            val sdf = SimpleDateFormat("yyyy年MM月", Locale.getDefault())
            imageList.groupBy { sdf.format(Date(it.lastModified)) }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            prefs.edit().putString("root_uri", uri.toString()).apply()
            rootUri = uri
        }
    }

    LaunchedEffect(Unit) {
        val savedUriString = prefs.getString("root_uri", null)
        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                if (context.contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                    rootUri = uri
                } else prefs.edit().remove("root_uri").apply()
            } catch (e: Exception) {
                prefs.edit().remove("root_uri").apply()
            }
        }
    }

    LaunchedEffect(rootUri) {
        if (rootUri != null) {
            isLoading = true
            withContext(Dispatchers.IO) {
                val rootDoc = DocumentFile.fromTreeUri(context, rootUri!!)
                if (rootDoc != null && rootDoc.exists()) {
                    val importedSet = loadImportedList(context, rootDoc)
                    val files = getAllImages(rootDoc).map { doc ->
                        ImageItem(
                            documentFile = doc,
                            isImported = importedSet.contains(doc.name),
                            lastModified = doc.lastModified()
                        )
                    }.sortedByDescending { it.lastModified } // ★ここで最新の日付が上になるように並び替え
                    imageList = files
                } else {
                    rootUri = null
                }
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GR3 Importer") },
                actions = {
                    if (rootUri != null) {
                        val selectedCount = imageList.count { it.isSelected }
                        if (selectedCount > 0) {
                            Text("${selectedCount}件選択中", modifier = Modifier.padding(end = 16.dp))
                            Button(
                                onClick = {
                                    val selectedItems = imageList.filter { it.isSelected }
                                    totalToImport = selectedItems.size
                                    importProgress = 0
                                    isImporting = true
                                    
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val rootDoc = DocumentFile.fromTreeUri(context, rootUri!!)
                                        val importedSet = loadImportedList(context, rootDoc!!).toMutableSet()
                                        
                                        for (item in selectedItems) {
                                            val success = saveToLocal(context, item.documentFile)
                                            if (success) importedSet.add(item.documentFile.name!!)
                                            importProgress++
                                        }
                                        saveImportedList(context, rootDoc, importedSet)
                                        
                                        val files = getAllImages(rootDoc).map { doc ->
                                            ImageItem(
                                                documentFile = doc,
                                                isImported = importedSet.contains(doc.name),
                                                lastModified = doc.lastModified()
                                            )
                                        }.sortedByDescending { it.lastModified }
                                        
                                        imageList = files
                                        isImporting = false
                                        
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "取り込みが完了しました", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isImporting
                            ) {
                                Text("スマホに取り込む")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (rootUri == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("SDカードの DCIM フォルダを選択してください", modifier = Modifier.padding(16.dp))
                    Button(onClick = { launcher.launch(null) }) { Text("フォルダを選択") }
                    Text("※初回のみ必要な操作です。\n次回以降は挿すだけで読み込まれます。", color = Color.Gray, modifier = Modifier.padding(top = 16.dp))
                }
            } else if (isLoading) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("SDカードをスキャン中...")
                }
            } else {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row {
                            Button(onClick = {
                                imageList = imageList.map { if (!it.isImported) it.copy(isSelected = true) else it }
                            }) { Text("すべて選択") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                imageList = imageList.map { it.copy(isSelected = false) }
                            }) { Text("選択解除") }
                        }
                        Button(onClick = { launcher.launch(null) }) { Text("フォルダ変更") }
                    }

                    // 画像一覧（画面に見えている部分だけを処理する賢いリスト）
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        groupedItems.forEach { (monthStr, items) ->
                            // ★月ごとの見出し
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = monthStr,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp).fillMaxWidth()
                                )
                            }
                            // ★その月の画像たち
                            items(items) { item ->
                                Box(
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .aspectRatio(1f)
                                        .clickable(enabled = !item.isImported && !isImporting) {
                                            imageList = imageList.map {
                                                if (it.documentFile.uri == item.documentFile.uri) {
                                                    it.copy(isSelected = !it.isSelected)
                                                } else it
                                            }
                                        }
                                ) {
                                    // 爆速サムネイル表示
                                    FastThumbnail(
                                        uri = item.documentFile.uri,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    if (item.isImported) {
                                        Box(
                                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                                            contentAlignment = Alignment.Center
                                        ) { Text("取込済", color = Color.White) }
                                    } else if (item.isSelected) {
                                        Box(modifier = Modifier.fillMaxSize().background(Color.Blue.copy(alpha = 0.4f)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (isImporting) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(modifier = Modifier.padding(32.dp)) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("取り込み中... ($importProgress / $totalToImport)")
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

// ★新機能：画面に表示された瞬間だけ、OSの高速なサムネイル生成を呼び出す
@Composable
fun FastThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    
    // このブロックは「画面に表示された時」に起動し、「画面から外れた時」に自動でキャンセルされます
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10以降：OS標準の超高速サムネイル抽出機能を利用
                    val bmp = context.contentResolver.loadThumbnail(uri, android.util.Size(400, 400), null)
                    bitmap = bmp.asImageBitmap()
                }
            } catch (e: Exception) {
                // 万が一失敗した場合は何もしない（下のCoilに任せる）
            }
        }
    }
    
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        // Android 9以前、または取得中のフォールバック
        Image(painter = rememberAsyncImagePainter(uri), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

fun getAllImages(dir: DocumentFile): List<DocumentFile> {
    val result = mutableListOf<DocumentFile>()
    for (file in dir.listFiles()) {
        if (file.isDirectory) {
            if (file.name?.startsWith(".") == false) {
                result.addAll(getAllImages(file))
            }
        } else if (file.isFile) {
            val name = file.name ?: ""
            if (name.endsWith(".JPG", true) || name.endsWith(".JPEG", true)) {
                result.add(file)
            }
        }
    }
    return result
}

fun loadImportedList(context: Context, directory: DocumentFile): Set<String> {
    val file = directory.findFile(".imported_list.json") ?: return emptySet()
    return try {
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
            val array = JSONArray(reader.readText())
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) set.add(array.getString(i))
            set
        } ?: emptySet()
    } catch (e: Exception) { emptySet() }
}

fun saveImportedList(context: Context, directory: DocumentFile, list: Set<String>) {
    var file = directory.findFile(".imported_list.json")
    if (file == null) file = directory.createFile("application/json", ".imported_list.json")
    file?.let { docFile ->
        try {
            val array = JSONArray()
            list.forEach { array.put(it) }
            context.contentResolver.openOutputStream(docFile.uri, "w")?.bufferedWriter()?.use { writer ->
                writer.write(array.toString())
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

fun saveToLocal(context: Context, documentFile: DocumentFile): Boolean {
    val resolver = context.contentResolver
    val fileName = documentFile.name ?: return false
    val mimeType = documentFile.type ?: "image/jpeg"

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GR3")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    resolver.openInputStream(documentFile.uri)?.use { input -> input.copyTo(out) }
                }
                true
            } else false
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GR3")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.outputStream().use { out ->
                resolver.openInputStream(documentFile.uri)?.use { input -> input.copyTo(out) }
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}