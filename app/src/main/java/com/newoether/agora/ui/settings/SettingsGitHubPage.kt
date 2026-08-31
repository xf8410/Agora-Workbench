package com.newoether.agora.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.github.GitHubAuthManager
import com.newoether.agora.github.GitHubDeviceCode
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

private data class RepoItem(val name: String, val branch: String)
private data class RemoteEntry(val name: String, val path: String, val type: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGitHubPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { GitHubAuthManager(context.applicationContext) }
    val client = remember { GitHubApiClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(manager.loadSession()) }
    var clientId by remember { mutableStateOf(manager.savedClientId()) }
    var token by remember { mutableStateOf("") }
    var deviceCode by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var openWorkspace by remember { mutableStateOf(false) }

    if (openWorkspace && session != null) {
        GitHubWorkspace(client, onBack = { openWorkspace = false })
        return
    }
    CollapsingSettingsScaffold(title = "GitHub Workbench", onBack = onBack) {
        SettingsGroupColumn {
            SettingsGroup(title = "Account", items = listOf({ Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (session != null) {
                    Text("Signed in as ${session!!.login}", style = MaterialTheme.typography.titleMedium)
                    if (session!!.scopes.isNotBlank()) Text("Scopes: ${session!!.scopes}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { openWorkspace = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(8.dp)); Text("Open code workspace") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { manager.logout(); session = null; status = "Signed out" }) { Text("Sign out") }
                } else Text("Sign in without placing credentials in chat, shell commands, URLs, or memory files.")
            } }))
            if (session == null) {
                SettingsGroup(title = "GitHub Device Flow", items = listOf({ Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(clientId, { clientId = it }, label = { Text("OAuth App Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !busy && clientId.isNotBlank(), onClick = { busy = true; status = "Requesting device code…"; scope.launch {
                        manager.requestDeviceCode(clientId).fold(onSuccess = { code -> deviceCode = code; status = "Enter code ${code.userCode} in GitHub"; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri))); manager.completeDeviceFlow(clientId, code).fold(onSuccess = { session = it; status = "Signed in as ${it.login}" }, onFailure = { status = it.message.orEmpty() }) }, onFailure = { status = it.message.orEmpty() }); busy = false
                    } }) { Text("Sign in with GitHub") }
                    deviceCode?.let { Spacer(Modifier.height(12.dp)); Text("Code: ${it.userCode}", style = MaterialTheme.typography.titleLarge); Text(it.verificationUri) }
                } }))
                SettingsGroup(title = "Fine-grained token (fallback)", items = listOf({ Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(token, { token = it }, label = { Text("GitHub token") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !busy && token.isNotBlank(), onClick = { busy = true; status = "Validating…"; scope.launch { manager.loginWithToken(token).fold(onSuccess = { session = it; token = ""; status = "Signed in as ${it.login}" }, onFailure = { status = it.message.orEmpty() }); busy = false } }) { Text("Validate and save") }
                } }))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubWorkspace(client: GitHubApiClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope(); val json = remember { Json { ignoreUnknownKeys = true } }
    var repos by remember { mutableStateOf<List<RepoItem>>(emptyList()) }; var repo by remember { mutableStateOf<RepoItem?>(null) }
    var branch by remember { mutableStateOf("") }; var newBranch by remember { mutableStateOf("workbench/mobile-edit") }; var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }; var filePath by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf("") }; var content by remember { mutableStateOf("") }; var commitMessage by remember { mutableStateOf("Edit from Agora Workbench") }
    var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }

    // 图片直传状态：选图后作为真实 Git 文件提交到当前 workbench 分支
    val uploadPrefs = remember { context.getSharedPreferences("agora_github_upload", Context.MODE_PRIVATE) }
    var pendingUpload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var uploadTarget by remember { mutableStateOf(uploadPrefs.getString("upload_path", "") ?: "") }
    var uploadMessage by remember { mutableStateOf("Upload image from Agora Workbench") }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val bytes = prepareImageUpload(raw)
            val name = "upload-" + System.currentTimeMillis() + ".jpg"
            pendingUpload = name to bytes
            uploadTarget = if (path.isEmpty()) name else path.trimEnd('/') + "/" + name
            status = "Selected " + (bytes.size / 1024) + " KB - check target path, then commit"
        }.onFailure { status = it.message ?: "Failed to read image" }
    }

    fun launch(block: suspend () -> Unit) = scope.launch { busy = true; try { block() } catch (e: Exception) { status = e.message ?: "GitHub operation failed" } finally { busy = false } }
    fun loadDirectory(target: String) = launch { val selected = repo ?: return@launch; val payload = client.readContent(selected.name, target, branch); entries = payload.jsonArray.map { val o=it.jsonObject; RemoteEntry(o["name"]!!.jsonPrimitive.content,o["path"]!!.jsonPrimitive.content,o["type"]!!.jsonPrimitive.content) }.sortedWith(compareBy<RemoteEntry>{it.type!="dir"}.thenBy{it.name.lowercase()}); path=target; filePath=null }
    LaunchedEffect(Unit) { launch { val r=client.request("GET","/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=50"); if(r.code !in 200..299) error("GitHub HTTP " + r.code); repos=json.parseToJsonElement(r.body).jsonArray.map { val o=it.jsonObject; RepoItem(o["full_name"]!!.jsonPrimitive.content,o["default_branch"]?.jsonPrimitive?.content?:"main") } } }
    Scaffold(topBar={ TopAppBar(title={Text(filePath?:repo?.name?:"Code workspace",maxLines=1)},navigationIcon={IconButton(onClick={when { filePath!=null->filePath=null; path.isNotEmpty()->loadDirectory(path.substringBeforeLast("/","")); repo!=null->{repo=null;entries=emptyList()}; else->onBack() }}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}}) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) { if(busy) LinearProgressIndicator(Modifier.fillMaxWidth()); if(status.isNotBlank()) Text(status,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.primary)
            when { repo==null -> LazyColumn(Modifier.fillMaxSize()){items(repos){item->ListItem(headlineContent={Text(item.name)},supportingContent={Text(item.branch)},leadingContent={Icon(Icons.Default.Folder,null)},modifier=Modifier.clickable{repo=item;branch=item.branch;loadDirectory("")})}}
                filePath!=null -> { OutlinedTextField(content,{content=it},modifier=Modifier.fillMaxWidth().weight(1f).padding(8.dp),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace),label={Text(filePath!!)}); OutlinedTextField(commitMessage,{commitMessage=it},modifier=Modifier.fillMaxWidth().padding(8.dp),label={Text("Commit message")}); Button(enabled=!busy&&content!=original&&branch.startsWith("workbench/"),onClick={launch{val selected=repo?:return@launch;client.writeFile(selected.name,filePath!!,branch,commitMessage,content);original=content;status="Committed to $branch"}},modifier=Modifier.fillMaxWidth().padding(8.dp)){Icon(Icons.Default.Save,null);Text(" Commit changes")} }
                else -> { Row(Modifier.fillMaxWidth().padding(8.dp)){OutlinedTextField(branch,{},readOnly=true,label={Text("Branch")},modifier=Modifier.weight(1f));IconButton(onClick={loadDirectory(path)}){Icon(Icons.Default.Refresh,"Refresh")}}; if(!branch.startsWith("workbench/")) Row(Modifier.fillMaxWidth().padding(8.dp)){OutlinedTextField(newBranch,{newBranch=it},label={Text("New workbench branch")},modifier=Modifier.weight(1f));Button(enabled=newBranch.startsWith("workbench/"),onClick={launch{val selected=repo?:return@launch;client.createBranch(selected.name,newBranch,branch);branch=newBranch;status="Created $newBranch";loadDirectory(path)}}){Text("Create")}}; if(branch.startsWith("workbench/")) { WorkspaceUploadCard(enabled=!busy,pending=pendingUpload,target=uploadTarget,onTarget={uploadTarget=it},message=uploadMessage,onMessage={uploadMessage=it},onPick={pickImage.launch("image/*")},onUpload={ launch { val selected=repo?:return@launch; val targetPath=uploadTarget.trim().trimStart('/'); val bytes=pendingUpload?.second?:return@launch; client.writeBinaryFile(selected.name,targetPath,branch,uploadMessage.ifBlank{"Upload image"},bytes); uploadPrefs.edit().putString("upload_path",targetPath).apply(); pendingUpload=null; status="Uploaded "+targetPath+" -> "+branch; loadDirectory(path.substringBeforeLast("/","")) } }) }; Text(if(path.isEmpty())"/" else "/$path",Modifier.padding(16.dp)); LazyColumn(Modifier.fillMaxSize()){items(entries){entry->ListItem(headlineContent={Text(entry.name)},leadingContent={Icon(if(entry.type=="dir")Icons.Default.Folder else Icons.Default.Description,null)},modifier=Modifier.clickable{if(entry.type=="dir")loadDirectory(entry.path) else launch{val selected=repo?:return@launch;val obj=client.readFile(selected.name,entry.path,branch);val raw=obj["content"]?.jsonPrimitive?.content?.replace("\n","").orEmpty();content=Base64.decode(raw,Base64.DEFAULT).toString(Charsets.UTF_8);original=content;filePath=entry.path;status=""}})}} }
            }
        }
    }
}

/** 图片直传卡片：选图 → 可编辑目标路径 → 真实 Git 提交到当前 workbench 分支 */
@Composable
private fun WorkspaceUploadCard(
    enabled: Boolean,
    pending: Pair<String, ByteArray>?,
    target: String,
    onTarget: (String) -> Unit,
    message: String,
    onMessage: (String) -> Unit,
    onPick: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Upload image to this branch") }
        pending?.let { item ->
            OutlinedTextField(target, onTarget, label = { Text("Target path (" + item.first + ", " + (item.second.size / 1024) + " KB)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true)
            OutlinedTextField(message, onMessage, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = onUpload, enabled = enabled && target.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Commit upload") }
        }
    }
}

/** 大图自动压缩：宽度超 1600 缩边，JPEG 质量自适应降到 600KB 以内（为 base64 留裕量） */
private fun prepareImageUpload(raw: ByteArray): ByteArray {
    if (raw.size <= 600_000) return raw
    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("Unsupported image format")
    val scaled = if (bmp.width > 1600) Bitmap.createScaledBitmap(bmp, 1600, bmp.height * 1600 / bmp.width, true) else bmp
    var quality = 88
    var out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    while (out.size() > 600_000 && quality > 55) { quality -= 12; out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) } }
    return out.toByteArray()
}
