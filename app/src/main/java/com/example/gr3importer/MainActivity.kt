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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

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

    // SAF (フォルダ選択) のランチャー
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Androidシステムに永続的なアクセス権限を要求
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            prefs.edit().putString("root_uri", uri.toString()).apply()
            rootUri = uri
        }
    }

    // 起動時の初期化：保存されたURIがあれば復元
    LaunchedEffect(Unit) {
        val savedUriString = prefs.getString("root_uri", null)
        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                val permissions = context.contentResolver.persistedUriPermissions
                if (permissions.any { it.uri == uri }) {
                    rootUri = uri
                } else {
                    prefs.edit().remove("root_uri").apply()
                }
            } catch (e: Exception) {
                prefs.edit().remove("root_uri").apply()
            }
        }
    }

    // URIがセットされたら、SDカード内の画像をスキャン
    LaunchedEffect(rootUri) {
        if (rootUri != null) {
            isLoading = true
            withContext(Dispatchers.IO) {
                val rootDoc = DocumentFile.fromTreeUri(context, rootUri!!)
                if (rootDoc != null && rootDoc.exists()) {
                    val importedSet = loadImportedList(context, rootDoc)
                    val files = getAllImages(rootDoc)
                        .map { doc ->
                            ImageItem(
                                documentFile = doc,
                                isImported = importedSet.contains(doc.name)
                            )
                        }
                        .sortedByDescending { it.documentFile.lastModified() } // 新しい順に並び替え
                    imageList = files
                } else {
                    rootUri = null // SDカードが抜かれている等のエラー
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
                                    
                                    // バックグラウンドでコピー処理を実行
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val rootDoc = DocumentFile.fromTreeUri(context, rootUri!!)
                                        val importedSet = loadImportedList(context, rootDoc!!).toMutableSet()
                                        
                                        for (item in selectedItems) {
                                            val success = saveToLocal(context, item.documentFile)
                                            if (success) {
                                                importedSet.add(item.documentFile.name!!)
                                            }
                                            importProgress++
                                        }
                                        // 隠しファイルを更新
                                        saveImportedList(context, rootDoc, importedSet)
                                        
                                        // スキャンし直して画面を更新
                                        val files = getAllImages(rootDoc).map { doc ->
                                            ImageItem(
                                                documentFile = doc,
                                                isImported = importedSet.contains(doc.name)
                                            )
                                        }.sortedByDescending { it.documentFile.lastModified() }
                                        
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
                // 初期画面（フォルダ未選択時）
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("SDカードの DCIM フォルダを選択してください", modifier = Modifier.padding(16.dp))
                    Button(onClick = { launcher.launch(null) }) {
                        Text("フォルダを選択")
                    }
                    Text(
                        "※初回のみ必要な操作です。\n次回以降は挿すだけで読み込まれます。",
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                // 画像一覧画面
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

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(imageList) { item ->
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .aspectRatio(1f)
                                    .clickable(enabled = !item.isImported && !isImporting) {
                                        // タップで選択/解除を切り替え
                                        imageList = imageList.map {
                                            if (it.documentFile.uri == item.documentFile.uri) {
                                                it.copy(isSelected = !it.isSelected)
                                            } else it
                                        }
                                    }
                            ) {
                                // サムネイル画像を表示
                                Image(
                                    painter = rememberAsyncImagePainter(item.documentFile.uri),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // 取り込み済みの場合は暗くする
                                if (item.isImported) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                                        contentAlignment = Alignment.Center
                                    ) { Text("取込済", color = Color.White) }
                                } else if (item.isSelected) {
                                    // 選択中の場合は青くハイライト
                                    Box(modifier = Modifier.fillMaxSize().background(Color.Blue.copy(alpha = 0.4f)))
                                }
                            }
                        }
                    }
                }
            }
            
            // 取り込み中のオーバーレイ表示
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

// --- ユーティリティ関数（裏方の処理） ---

// フォルダ内の画像を再帰的に探す（100RICOHなどのサブフォルダにも対応）
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

// 隠しファイルから取込済みのリストを読み込む
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

// 隠しファイルに取込済みのリストを書き込む
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

// スマホ本体の Pictures/GR3 フォルダに画像をコピーする
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
            // Android 9 以前用
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