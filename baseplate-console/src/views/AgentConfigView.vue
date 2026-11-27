<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">智能体配置</h2>
        <p class="page-desc">
          每个 Agent 绑定一组页面与工具：页面决定入口，工具决定能做什么，审批策略决定是否要人工确认。
          内置工具只读，只能启停与调整审批策略。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button :icon="MagicStick" @click="openSkills">技能</el-button>
        <el-button :icon="Tools" @click="openToolDirectory">工具目录</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建智能体</el-button>
      </div>
    </div>

    <el-table :data="definitions" size="small" v-loading="loading">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <div class="name-cell">
            <b>{{ row.name }}</b>
            <span class="code-chip">{{ row.agentCode }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="模型" min-width="200">
        <template #default="{ row }">
          <div>{{ row.provider }}</div>
          <div class="muted">{{ row.modelName }}</div>
        </template>
      </el-table-column>
      <el-table-column label="页面" min-width="180">
        <template #default="{ row }">
          <span v-for="page in row.allowedPages" :key="page" class="pill">{{ page }}</span>
        </template>
      </el-table-column>
      <el-table-column label="密钥" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.apiKeyConfigured ? 'success' : 'warning'" effect="plain">
            {{ row.apiKeyConfigured ? '已配置' : '未配置' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="70" prop="revision" />
      <el-table-column label="操作" width="360" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openTools(row)">工具策略</el-button>
          <el-button link type="primary" size="small" @click="openAgentSkills(row)">技能</el-button>
          <el-button link type="primary" size="small" :loading="testingId === row.id"
                     @click="testConnection(row)">测试连接</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !definitions.length" description="还没有智能体，点击右上角新建" :image-size="80" />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑智能体' : '新建智能体'" width="760px">
      <el-form :model="form" label-width="110px">
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="编码" required>
              <el-input v-model="form.agentCode" :disabled="!!editingId" placeholder="小写字母/数字/中划线" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" placeholder="例如 流程助手" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="这个 Agent 负责什么" />
        </el-form-item>

        <el-divider content-position="left">模型</el-divider>
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="厂商" required>
              <el-select v-model="form.provider" style="width: 100%">
                <el-option v-for="item in providers" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型名" required>
              <el-input v-model="form.modelName" placeholder="例如 MiniMax-M3.1-Flash" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="API 地址">
          <el-input v-model="form.baseUrl" placeholder="留空使用默认端点" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="editingId ? '留空表示保持原密钥不变' : 'sk-...'" />
          <div class="hint">密钥以 AES-GCM 密文存储，接口不会回显。留空时回退到 .env 的默认 Key。</div>
        </el-form-item>

        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="上下文窗口">
              <el-input-number v-model="form.contextWindow" :min="1000" :step="1000"
                               controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="输出上限">
              <el-input-number v-model="form.maxOutputTokens" :min="128" :step="128"
                               controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="最大循环次数">
              <el-input-number v-model="form.maxIters" :min="1" :max="1000" :disabled="form.unlimitedIters"
                               controls-position="right" style="width: 100%" />
              <div class="hint">ReAct 最多执行多少轮工具调用；勾选不限次数则直到模型自行结束。</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="循环上限">
              <el-checkbox v-model="form.unlimitedIters">不限次数（无限循环）</el-checkbox>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="思考深度">
              <el-select v-model="form.reasoningEffort" style="width: 100%" clearable
                         placeholder="默认">
                <el-option label="low" value="low" />
                <el-option label="medium" value="medium" />
                <el-option label="high" value="high" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="温度">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1"
                               :precision="1" controls-position="right" style="width: 100%"
                               placeholder="默认" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">提示词与作用域</el-divider>
        <el-form-item label="系统提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4"
                    placeholder="留空使用默认提示词" />
        </el-form-item>
        <el-form-item label="允许页面" required>
          <el-select v-model="form.pages" multiple filterable allow-create default-first-option
                     style="width: 100%" placeholder="选择页面，或输入自定义页面 key">
            <el-option v-for="item in PAGE_OPTIONS" :key="item.value"
                       :label="`${item.label}（${item.value}）`" :value="item.value" />
          </el-select>
          <div class="hint">页面 key 取路由路径去掉前导斜杠；选「全部页面」表示所有页面。</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" active-value="ENABLED" inactive-value="DISABLED"
                     active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="skillsVisible" title="技能管理" width="920px">
      <div class="hint" style="margin-bottom: 10px">
        技能正文在服务端 <code>baseplate/skills</code> 目录（每个技能一个子目录 + SKILL.md），
        数据库只保存索引与有效性。支持在线编辑，也可以上传 zip 或整个技能文件夹（文件夹名即技能名）。
      </div>
      <div class="skill-toolbar">
        <el-input v-model="skillKeyword" placeholder="搜索名称、描述或文件" clearable
                  :prefix-icon="Search" style="width: 240px" />
        <el-button size="small" type="primary" :icon="Plus" @click="openCreateSkill">新建技能</el-button>
        <el-button size="small" @click="skillZipInput?.click()">上传 zip</el-button>
        <el-button size="small" @click="skillFolderInput?.click()">上传文件夹</el-button>
        <el-button size="small" :icon="Refresh" :loading="skillsLoading" @click="rescanSkills">重新扫描</el-button>
        <input ref="skillZipInput" type="file" accept=".zip" style="display: none"
               @change="onSkillZipPicked" />
        <input ref="skillFolderInput" type="file" webkitdirectory multiple style="display: none"
               @change="onSkillFolderPicked" />
      </div>
      <el-table :data="filteredSkills" size="small" v-loading="skillsLoading">
        <el-table-column prop="skillName" label="技能" width="180" />
        <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'VALID' ? 'success' : 'danger'" effect="plain">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="文件" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <!-- MISSING 意味着文件已被删掉，编辑没有意义，只保留删除用于清理索引 -->
            <el-button v-if="row.status === 'VALID'" link type="primary" size="small"
                       @click="openEditSkill(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeSkill(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!skillsLoading && !skills.length" description="skills 目录还没有技能" :image-size="70" />
      <template #footer>
        <el-button @click="skillsVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="skillFormVisible" :title="skillEditing ? '编辑技能' : '新建技能'" width="780px">
      <el-form label-width="90px">
        <el-form-item label="技能名" required>
          <el-input v-model="skillForm.skillName" :disabled="!!skillEditing"
                    placeholder="目录名，例如 发一条供需" />
        </el-form-item>
        <el-form-item v-if="!skillEditing" label="描述">
          <el-input v-model="skillForm.description" placeholder="一句话说明什么时候用这个技能" />
        </el-form-item>
        <el-form-item v-if="!skillEditing" label="适用模式">
          <el-input v-model="skillForm.modes" placeholder="cli / meta，多个用逗号分隔；留空则不按模式自动挂载" />
          <div class="hint">
            填写 cli 后该技能只在 CLI 模式挂载，meta 同理；也可以在 SKILL.md 的 frontmatter 里直接写 modes: cli
          </div>
        </el-form-item>
        <el-form-item label="SKILL.md" required>
          <el-input v-model="skillForm.content" type="textarea" :rows="16"
                    placeholder="支持 Markdown；新建时可以不写 frontmatter，保存时自动补 name/description/version" />
          <div class="hint">
            编辑时直接改全文（包含 --- frontmatter ---）；描述与版本以文件内容为准，保存后会重新扫描索引。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="skillFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="skillSaving" @click="saveSkill">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="toolDirVisible" title="工具目录" width="900px">
      <div class="hint" style="margin-bottom: 10px">
        内置工具只读；自定义工具当前支持「外部工具」：服务端下发参数，由页面执行后回传结果。
      </div>
      <div style="margin-bottom: 10px">
        <el-button size="small" type="primary" :icon="Plus" @click="openCreateTool">新建外部工具</el-button>
      </div>
      <el-table :data="toolDirectory" size="small" v-loading="toolDirLoading">
        <el-table-column label="工具" min-width="260">
          <template #default="{ row }">
            <div class="name-cell">
              <b>{{ row.name }}</b>
              <el-tag size="small" :type="toolTypeTag(row.toolType)" effect="plain">
                {{ toolTypeText(row.toolType) }}
              </el-tag>
            </div>
            <div class="muted">{{ row.toolCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="页面" min-width="150">
          <template #default="{ row }">
            <span v-if="!row.pages?.length" class="muted">不限</span>
            <span v-for="page in row.pages" :key="page" class="pill">{{ page }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="riskLevel" label="风险" width="110" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <template v-if="row.editable">
              <el-button link type="primary" size="small" @click="editTool(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="removeTool(row)">删除</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="toolFormVisible" :title="toolEditing ? '编辑外部工具' : '新建外部工具'" width="700px">
      <el-form :model="toolForm" label-width="110px">
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="编码" required>
              <el-input v-model="toolForm.toolCode" :disabled="!!toolEditing" placeholder="小写字母/数字/下划线" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="toolForm.name" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="说明">
          <el-input v-model="toolForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="toolForm.toolType">
            <el-radio value="EXTERNAL">外部工具（前端执行）</el-radio>
            <el-radio value="CUSTOM_INTERNAL">内部 HTTP（服务端执行）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="platformTools.length" label="平台注册端点">
          <div class="hint">
            以下端点已由开发平台注册并保管凭证，会自动投影成 Agent 工具；无需在页面填写 URL 或密钥。
          </div>
          <el-table :data="platformTools" size="small" style="width: 100%; margin-top: 6px">
            <el-table-column prop="code" label="工具编码" min-width="140" />
            <el-table-column prop="name" label="名称" min-width="130" />
            <el-table-column label="执行方式" width="120">
              <template #default="{ row }">
                <el-tag :type="row.readonly ? 'success' : 'warning'" size="small">
                  {{ row.readonly ? '服务端只读' : '前端执行' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="serviceCode" label="服务" min-width="110" />
          </el-table>
        </el-form-item>
        <el-row v-if="toolForm.toolType === 'CUSTOM_INTERNAL'" :gutter="14">
          <el-col :span="16">
            <el-form-item label="URL" required>
              <el-input v-model="toolForm.url" placeholder="https://example.com/api/tool" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="方法">
              <el-select v-model="toolForm.method" style="width: 100%">
                <el-option label="POST" value="POST" />
                <el-option label="GET" value="GET" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="页面" required>
          <el-select v-model="toolForm.pages" multiple filterable allow-create default-first-option
                     style="width: 100%" placeholder="选择页面，或输入自定义页面 key">
            <el-option v-for="item in PAGE_OPTIONS" :key="item.value"
                       :label="`${item.label}（${item.value}）`" :value="item.value" />
          </el-select>
          <div class="hint">工具只会在这些页面展示，也只会被这些页面的智能体调用。</div>
        </el-form-item>
        <el-form-item label="所需权限">
          <el-select v-model="toolForm.permissionCode" filterable allow-create clearable
                     default-first-option style="width: 100%"
                     placeholder="可空；从当前账号权限选择，或输入自定义权限码">
            <el-option v-for="code in permissionOptions" :key="code" :label="code" :value="code" />
          </el-select>
        </el-form-item>
        <el-row :gutter="14">
          <el-col :span="8">
            <el-form-item label="风险级别">
              <el-select v-model="toolForm.riskLevel" style="width: 100%">
                <el-option label="READ" value="READ" />
                <el-option label="WRITE" value="WRITE" />
                <el-option label="DESTRUCTIVE" value="DESTRUCTIVE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="安全下限">
              <el-select v-model="toolForm.minApproval" style="width: 100%">
                <el-option label="直接执行" value="ALLOW" />
                <el-option label="需确认" value="ASK" />
                <el-option label="禁用" value="DENY" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="默认策略">
              <el-select v-model="toolForm.defaultApproval" style="width: 100%">
                <el-option label="直接执行" value="ALLOW" />
                <el-option label="需确认" value="ASK" />
                <el-option label="禁用" value="DENY" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="参数 Schema">
          <div style="width: 100%">
            <el-radio-group v-model="schemaMode" size="small" style="margin-bottom: 8px">
              <el-radio-button value="visual">可视化</el-radio-button>
              <el-radio-button value="json">JSON</el-radio-button>
            </el-radio-group>
            <div v-if="schemaMode === 'visual'">
              <SchemaFieldEditor v-model="schemaParams" />
            </div>
            <el-input v-else v-model="toolForm.paramsText" type="textarea" :rows="8"
                      placeholder='{"type":"object","properties":{"field":{"type":"string"}}}' />
            <div class="hint">
              模型按此 schema 生成调用参数；object 可展开子字段，array 可选元素类型（元素为 object 时同样可展开）。
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="toolFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="toolSaving" @click="saveTool">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="toolsVisible" title="工具与审批策略" width="900px">
      <div class="hint" style="margin-bottom: 10px">
        只显示与当前智能体「允许页面」有交集的工具；跨页面工具不会出现在这里，也不会被模型调用。
        生效策略 = 更严格（工具安全下限，下面配置的策略）。破坏性工具最低为「需人工确认」，不能改成直接执行。
      </div>
      <el-table :data="toolRows" size="small" v-loading="toolsLoading">
        <el-table-column label="工具" min-width="240">
          <template #default="{ row }">
            <div class="name-cell">
              <b>{{ row.name }}</b>
              <el-tag size="small" :type="toolTypeTag(row.toolType)" effect="plain">
                {{ toolTypeText(row.toolType) }}
              </el-tag>
            </div>
            <div class="muted">{{ row.toolCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="页面" min-width="140">
          <template #default="{ row }">
            <span v-if="!row.pages?.length" class="muted">不限</span>
            <span v-for="page in row.pages" :key="page" class="pill">{{ page }}</span>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.riskLevel === 'DESTRUCTIVE' ? 'danger' : (row.riskLevel === 'WRITE' ? 'warning' : 'info')">
              {{ row.riskLevel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权限" width="110" prop="permissionCode">
          <template #default="{ row }">{{ row.permissionCode || '—' }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" />
          </template>
        </el-table-column>
        <el-table-column label="审批策略" width="150">
          <template #default="{ row }">
            <el-select v-model="row.approvalMode" size="small" style="width: 100%">
              <el-option label="跟随默认" value="INHERIT" />
              <el-option label="直接执行" value="ALLOW" :disabled="row.minApproval === 'ASK'" />
              <el-option label="需人工确认" value="ASK" />
              <el-option label="禁用" value="DENY" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="生效" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain"
                    :type="row.effectiveApproval === 'DENY' ? 'danger' : (row.effectiveApproval === 'ASK' ? 'warning' : 'success')">
              {{ approvalText(row.effectiveApproval) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="toolsVisible = false">取消</el-button>
        <el-button type="primary" :loading="toolsSaving" @click="saveTools">保存策略</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="agentSkillsVisible" title="技能绑定" width="760px">
      <div class="hint" style="margin-bottom: 10px">
        技能正文在服务端 skills 目录；这里只决定当前智能体能加载哪些技能。
        全部关闭表示该智能体不使用任何技能。
      </div>
      <el-table :data="agentSkills" size="small" v-loading="agentSkillsLoading">
        <el-table-column prop="skillName" label="技能" width="200" />
        <el-table-column label="描述" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'VALID' ? 'success' : 'danger'" effect="plain">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" />
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!agentSkillsLoading && !agentSkills.length" description="skills 目录还没有技能"
                :image-size="60" />
      <template #footer>
        <el-button @click="agentSkillsVisible = false">取消</el-button>
        <el-button type="primary" :loading="agentSkillsSaving" @click="saveAgentSkills">保存绑定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MagicStick, Search, Tools } from '@element-plus/icons-vue'
import {
  agentApi,
  type AgentDefinitionView,
  type AgentSkillBindingView,
  type AgentToolView
} from '../api/agent'
import http, { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'
import SchemaFieldEditor from '../components/agent/SchemaFieldEditor.vue'
import {
  nodesToSchema,
  schemaFitsVisual,
  schemaToNodes,
  type SchemaNode
} from '../components/agent/schemaField'

const providers = [
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' },
  { value: 'DASHSCOPE', label: 'DashScope（通义）' },
  { value: 'ANTHROPIC', label: 'Anthropic（Claude）' },
  { value: 'GEMINI', label: 'Gemini' },
  { value: 'OLLAMA', label: 'Ollama（本地）' }
]

/** 与 router/index.ts 的页面保持一致；新增页面时两边一起加。 */
const PAGE_OPTIONS = [
  { value: '*', label: '全部页面' },
  { value: 'dashboard', label: '概览' },
  { value: 'identity/users', label: '用户管理' },
  { value: 'identity/roles', label: '角色权限' },
  { value: 'identity/orgs', label: '组织架构' },
  { value: 'platform/apps', label: '开放平台' },
  { value: 'platform/apis', label: '接口目录' },
  { value: 'audit/logs', label: '审计日志' },
  { value: 'rule/config', label: '规则引擎' },
  { value: 'workflow/start', label: '发起流程' },
  { value: 'workflow/todo', label: '我的待办' },
  { value: 'workflow/mine', label: '我发起的' },
  { value: 'workflow/designer', label: '工作流' },
  { value: 'workflow/forms', label: '流程表单列表' },
  { value: 'workflow/forms/design', label: '表单设计' },
  { value: 'workflow/lab', label: '流程演练' },
  { value: 'agent/config', label: '智能体配置' }
]

const auth = useAuthStore()
/** 权限下拉的候选值：当前账号拥有的权限。允许手输，覆盖给其他角色用的场景。 */
const permissionOptions = computed(() => auth.user?.permissions || [])

const definitions = ref<AgentDefinitionView[]>([])
const loading = ref(false)
const saving = ref(false)
const testingId = ref<number | null>(null)

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  agentCode: '',
  name: '',
  description: '',
  provider: 'OPENAI_COMPATIBLE',
  modelName: '',
  baseUrl: '',
  apiKey: '',
  systemPrompt: '',
  contextWindow: 1000000,
  maxOutputTokens: 4096,
  maxIters: 10,
  unlimitedIters: false,
  reasoningEffort: 'low' as string,
  temperature: 0.3 as number | '',
  pages: ['*'] as string[],
  status: 'ENABLED'
})

const toolsVisible = ref(false)
const toolsLoading = ref(false)
const toolsSaving = ref(false)
const toolRows = ref<AgentToolView[]>([])
const currentAgentId = ref<number | null>(null)

const skillsVisible = ref(false)
const skillsLoading = ref(false)
const skills = ref<any[]>([])
const skillKeyword = ref('')
const skillZipInput = ref<HTMLInputElement | null>(null)
const skillFolderInput = ref<HTMLInputElement | null>(null)
const skillFormVisible = ref(false)
const skillSaving = ref(false)
const skillEditing = ref<string | null>(null)
const skillForm = reactive({ skillName: '', description: '', modes: '', content: '' })

/** 列表通常只有几十条，前端过滤足够，不额外加接口。 */
const filteredSkills = computed(() => {
  const keyword = skillKeyword.value.trim().toLowerCase()
  if (!keyword) return skills.value
  return skills.value.filter((skill) => {
    const text = `${skill.skillName || ''} ${skill.description || ''} ${skill.path || ''}`
    return text.toLowerCase().includes(keyword)
  })
})

/** 技能绑定弹窗：单独针对某个 Agent，和全局技能索引不是一回事。 */
const agentSkillsVisible = ref(false)
const agentSkillsLoading = ref(false)
const agentSkillsSaving = ref(false)
const agentSkills = ref<AgentSkillBindingView[]>([])
const skillAgentId = ref<number | null>(null)

const toolDirVisible = ref(false)
const toolDirLoading = ref(false)
const toolDirectory = ref<any[]>([])
const toolFormVisible = ref(false)
const toolSaving = ref(false)
const toolEditing = ref<string | null>(null)
const platformTools = ref<any[]>([])
const schemaMode = ref<'visual' | 'json'>('visual')
const schemaParams = ref<SchemaNode[]>([])
const toolForm = reactive({
  toolCode: '',
  name: '',
  description: '',
  toolType: 'EXTERNAL',
  url: '',
  method: 'POST',
  pages: ['workflow/designer'] as string[],
  permissionCode: '',
  riskLevel: 'WRITE',
  minApproval: 'ALLOW',
  defaultApproval: 'ALLOW',
  paramsText: '{\n  "type": "object",\n  "properties": {}\n}'
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const { data } = await agentApi.list()
    definitions.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    agentCode: '',
    name: '',
    description: '',
    provider: 'OPENAI_COMPATIBLE',
    modelName: '',
    baseUrl: '',
    apiKey: '',
    systemPrompt: '',
    contextWindow: 1000000,
    maxOutputTokens: 4096,
    maxIters: 10,
    unlimitedIters: false,
    reasoningEffort: 'low',
    temperature: 0.3,
    pages: ['*'],
    status: 'ENABLED'
  })
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: AgentDefinitionView) {
  editingId.value = row.id
  const params = row.modelParams || {}
  Object.assign(form, {
    agentCode: row.agentCode,
    name: row.name,
    description: row.description || '',
    provider: row.provider,
    modelName: row.modelName,
    baseUrl: row.baseUrl || '',
    apiKey: '',
    systemPrompt: row.systemPrompt || '',
    contextWindow: Number(params.contextWindow || 1000000),
    maxOutputTokens: Number(params.maxOutputTokens || 4096),
    maxIters: Number(params.maxIters) > 0 ? Number(params.maxIters) : 10,
    unlimitedIters: Number(params.maxIters) < 0,
    reasoningEffort: params.reasoningEffort || '',
    temperature: params.temperature === undefined ? '' : Number(params.temperature),
    pages: [...(row.allowedPages || [])],
    status: row.status
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim() || !form.modelName.trim() || !form.pages.length) {
    ElMessage.warning('名称、模型名、允许页面为必填项')
    return
  }
  saving.value = true
  try {
    const params: Record<string, any> = {
      contextWindow: form.contextWindow,
      maxOutputTokens: form.maxOutputTokens,
      maxIters: form.unlimitedIters ? -1 : form.maxIters
    }
    if (form.reasoningEffort) params.reasoningEffort = form.reasoningEffort
    if (form.temperature !== '' && form.temperature !== null) params.temperature = form.temperature
    const payload = {
      agentCode: form.agentCode.trim(),
      name: form.name.trim(),
      description: form.description.trim(),
      provider: form.provider,
      modelName: form.modelName.trim(),
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim(),
      systemPrompt: form.systemPrompt,
      modelParams: params,
      allowedPages: form.pages,
      status: form.status
    }
    if (editingId.value) {
      await agentApi.update(editingId.value, payload)
    } else {
      await agentApi.create(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: AgentDefinitionView) {
  try {
    await ElMessageBox.confirm(`确认删除智能体「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await agentApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function testConnection(row: AgentDefinitionView) {
  testingId.value = row.id
  try {
    const { data } = await agentApi.test(row.id)
    ElMessage.success(`连接成功，耗时 ${data.latencyMs} ms，回复：${data.reply || '（空）'}`)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    testingId.value = null
  }
}

async function openTools(row: AgentDefinitionView) {
  currentAgentId.value = row.id
  toolsVisible.value = true
  toolsLoading.value = true
  try {
    const { data } = await agentApi.tools(row.id)
    toolRows.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    toolsLoading.value = false
  }
}

async function openSkills() {
  skillsVisible.value = true
  await refreshSkills()
}

async function refreshSkills() {
  skillsLoading.value = true
  try {
    const { data } = await agentApi.skills()
    skills.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    skillsLoading.value = false
  }
}

async function rescanSkills() {
  skillsLoading.value = true
  try {
    const { data } = await agentApi.rescanSkills()
    ElMessage.success(`已扫描 ${data.scanned} 个技能`)
    await refreshSkills()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    skillsLoading.value = false
  }
}

function openCreateSkill() {
  skillEditing.value = null
  Object.assign(skillForm, { skillName: '', description: '', modes: '', content: '' })
  skillFormVisible.value = true
}

async function openEditSkill(row: any) {
  skillEditing.value = row.skillName
  try {
    const { data } = await agentApi.skillDetail(row.skillName)
    Object.assign(skillForm, {
      skillName: data.skillName,
      description: data.description || '',
      content: data.content || ''
    })
    skillFormVisible.value = true
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function saveSkill() {
  if (!skillForm.skillName.trim() || !skillForm.content.trim()) {
    ElMessage.warning('技能名与 SKILL.md 正文为必填项')
    return
  }
  skillSaving.value = true
  try {
    if (skillEditing.value) {
      await agentApi.updateSkill(skillEditing.value, skillForm.content)
    } else {
      await agentApi.createSkill({
        skillName: skillForm.skillName.trim(),
        description: skillForm.description.trim(),
        modes: skillForm.modes.trim(),
        content: skillForm.content
      })
    }
    ElMessage.success('技能已保存')
    skillFormVisible.value = false
    await refreshSkills()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    skillSaving.value = false
  }
}

async function removeSkill(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除技能「${row.skillName}」及其目录？`, '删除确认',
      { type: 'warning' })
  } catch {
    return
  }
  try {
    await agentApi.deleteSkill(row.skillName)
    ElMessage.success('技能已删除')
    await refreshSkills()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function onSkillZipPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  skillsLoading.value = true
  try {
    const { data } = await agentApi.uploadSkillZip(file)
    ElMessage.success(`已导入 ${data.imported} 个技能`)
    await refreshSkills()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    skillsLoading.value = false
  }
}

/** 上传的是「一个技能目录」：目录名作为技能名，内部脚本等资源原样保留。 */
async function onSkillFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!files.length) return
  skillsLoading.value = true
  try {
    const { data } = await agentApi.uploadSkillFolder(files)
    ElMessage.success(`已导入 ${data.imported} 个技能`)
    await refreshSkills()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    skillsLoading.value = false
  }
}

async function openToolDirectory() {
  toolDirVisible.value = true
  await refreshToolDirectory()
}

async function refreshToolDirectory() {
  toolDirLoading.value = true
  try {
    const { data } = await agentApi.toolDirectory()
    toolDirectory.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    toolDirLoading.value = false
  }
}

async function loadPlatformTools() {
  try {
    const { data } = await http.get('/api/integration/proxy/services')
    platformTools.value = Array.isArray(data) ? data : []
  } catch {
    platformTools.value = []
  }
}

function openCreateTool() {
  void loadPlatformTools()
  toolEditing.value = null
  Object.assign(toolForm, {
    toolCode: '',
    name: '',
    description: '',
    toolType: 'EXTERNAL',
    url: '',
    method: 'POST',
    pages: ['workflow/designer'],
    permissionCode: '',
    riskLevel: 'WRITE',
    minApproval: 'ALLOW',
    defaultApproval: 'ALLOW',
    paramsText: '{\n  "type": "object",\n  "properties": {}\n}'
  })
  schemaMode.value = 'visual'
  schemaParams.value = []
  toolFormVisible.value = true
}

function editTool(row: any) {
  void loadPlatformTools()
  toolEditing.value = row.toolCode
  const schema = row.paramsSchema || { type: 'object', properties: {} }
  Object.assign(toolForm, {
    toolCode: row.toolCode,
    name: row.name,
    description: row.description || '',
    toolType: row.toolType,
    url: row.config?.url || '',
    method: row.config?.method || 'POST',
    pages: [...(row.pages || [])],
    permissionCode: row.permissionCode || '',
    riskLevel: row.riskLevel,
    minApproval: row.minApproval,
    defaultApproval: row.defaultApproval,
    paramsText: JSON.stringify(schema, null, 2)
  })
  schemaParams.value = schemaToNodes(schema)
  // 嵌套结构在可视化模式里表达不了，直接落到 JSON，避免保存时丢字段
  syncingSchema = true
  schemaMode.value = schemaFitsVisual(schema) ? 'visual' : 'json'
  void nextTick(() => { syncingSchema = false })
  toolFormVisible.value = true
}

/** editTool 回填时不要让 watch 把已经解析好的复杂 schema 重新序列化压平。 */
let syncingSchema = false

/** 切换模式时互相同步，两种编辑方式始终指向同一份 schema。 */
watch(schemaMode, (mode) => {
  if (syncingSchema) return
  if (mode === 'json') {
    toolForm.paramsText = JSON.stringify(nodesToSchema(schemaParams.value), null, 2)
  } else {
    schemaParams.value = schemaToNodes(parseSchemaText())
  }
})

function parseSchemaText(): Record<string, any> {
  try {
    return JSON.parse(toolForm.paramsText || '{}')
  } catch {
    return {}
  }
}

async function saveTool() {
  if (!toolForm.toolCode.trim() || !toolForm.name.trim() || !toolForm.pages.length) {
    ElMessage.warning('编码、名称、页面为必填项')
    return
  }
  let paramsSchema: Record<string, any>
  if (schemaMode.value === 'visual') {
    paramsSchema = nodesToSchema(schemaParams.value)
    toolForm.paramsText = JSON.stringify(paramsSchema, null, 2)
  } else {
    try {
      paramsSchema = JSON.parse(toolForm.paramsText || '{}')
    } catch {
      ElMessage.error('参数 Schema 不是合法 JSON')
      return
    }
  }
  const payload = {
    toolCode: toolForm.toolCode.trim(),
    name: toolForm.name.trim(),
    description: toolForm.description.trim(),
    toolType: toolForm.toolType,
    pages: toolForm.pages,
    permissionCode: toolForm.permissionCode.trim() || undefined,
    riskLevel: toolForm.riskLevel,
    minApproval: toolForm.minApproval,
    defaultApproval: toolForm.defaultApproval,
    paramsSchema,
    config: toolForm.toolType === 'CUSTOM_INTERNAL'
      ? { url: toolForm.url.trim(), method: toolForm.method }
      : {}
  }
  if (toolForm.toolType === 'CUSTOM_INTERNAL' && !toolForm.url.trim()) {
    ElMessage.warning('内部 HTTP 工具必须填写 URL')
    return
  }
  toolSaving.value = true
  try {
    if (toolEditing.value) {
      await agentApi.updateTool(toolEditing.value, payload)
    } else {
      await agentApi.createTool(payload)
    }
    ElMessage.success('工具已保存')
    toolFormVisible.value = false
    await refreshToolDirectory()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    toolSaving.value = false
  }
}

async function removeTool(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除外部工具「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await agentApi.deleteTool(row.toolCode)
    ElMessage.success('已删除')
    await refreshToolDirectory()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function saveTools() {
  if (!currentAgentId.value) return
  toolsSaving.value = true
  try {
    await agentApi.saveTools(currentAgentId.value, toolRows.value.map((row) => ({
      toolCode: row.toolCode,
      enabled: row.enabled,
      approvalMode: row.approvalMode
    })))
    ElMessage.success('策略已保存')
    toolsVisible.value = false
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    toolsSaving.value = false
  }
}

async function openAgentSkills(row: AgentDefinitionView) {
  skillAgentId.value = row.id
  agentSkillsVisible.value = true
  agentSkillsLoading.value = true
  try {
    const { data } = await agentApi.skillsOfAgent(row.id)
    agentSkills.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    agentSkillsLoading.value = false
  }
}

async function saveAgentSkills() {
  if (!skillAgentId.value) return
  agentSkillsSaving.value = true
  try {
    // 全量提交（含未勾选），后端据此判断「配置过但全部关闭」
    await agentApi.saveAgentSkills(skillAgentId.value, agentSkills.value.map((row) => ({
      skillName: row.skillName,
      enabled: row.enabled
    })))
    ElMessage.success('技能绑定已保存')
    agentSkillsVisible.value = false
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    agentSkillsSaving.value = false
  }
}

function toolTypeText(type: string) {
  return ({
    EXTERNAL: '外部工具',
    CUSTOM_INTERNAL: '内部 HTTP',
    BUILTIN_INTERNAL: '内置'
  } as Record<string, string>)[type] || type
}

function toolTypeTag(type: string): 'warning' | 'success' | 'info' {
  if (type === 'EXTERNAL') return 'warning'
  if (type === 'CUSTOM_INTERNAL') return 'success'
  return 'info'
}

function approvalText(value: string) {
  return ({ ALLOW: '直接执行', ASK: '需确认', DENY: '禁用', INHERIT: '跟随默认' } as Record<string, string>)[value] || value
}
</script>

<style scoped>
.name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.muted {
  color: var(--bp-muted);
  font-size: 11px;
}
.hint {
  font-size: 11px;
  color: var(--bp-muted);
  line-height: 1.6;
}
.pill {
  display: inline-block;
  margin: 1px 4px 1px 0;
  padding: 1px 8px;
  border-radius: 999px;
  background: #F1F4F2;
  border: 1px solid var(--bp-line);
  font-size: 11px;
  color: var(--bp-muted);
}
.skill-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
</style>
