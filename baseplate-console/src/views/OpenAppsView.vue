<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">开放平台</h2>
        <p class="page-desc">
          每个应用有独立的 AppKey、签名密钥和可调用 API 清单。第三方调用时，日志会记录是哪个应用、调了哪个 API、从哪个 IP 来。
        </p>
      </div>
      <div class="page-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">创建应用</el-button>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <span class="hint">共 {{ apps.length }} 个应用</span>
        <div class="spacer"></div>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <el-table :data="apps" v-loading="loading">
        <el-table-column label="应用" min-width="240">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 10px">
              <div class="avatar" style="background: #EAF0F8; color: #2C5A8C">
                {{ row.appName.slice(0, 1).toUpperCase() }}
              </div>
              <div>
                <div style="font-weight: 600">{{ row.appName }}</div>
                <div class="mono hint">{{ row.appKey }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="认证方式" width="200">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ authModeLabel(row.authMode) }}</el-tag>
            <div class="hint" style="margin-top: 5px; line-height: 1.5">{{ grantLabel(row.grantTypes) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="已授权 API" width="140">
          <template #default="{ row }">
            <el-tooltip content="点击查看并配置该应用可调用的开放 API" placement="top">
              <span class="api-count" @click="openDetail(row, 'apis')">
                <span class="pill green">{{ row.grantedApiCount }} / {{ activeCatalog.length }}</span>
                <span class="link-text">配置</span>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="配额" width="150">
          <template #default="{ row }">
            <div>{{ row.qpsLimit }} QPS</div>
            <div class="hint">{{ fmtDay(row.dayLimit) }} / 日</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row, 'overview')">详情</el-button>
            <el-button link @click="openDetail(row, 'logs')">调用日志</el-button>
            <el-button link @click="openDetail(row, 'apis')">API 授权</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 应用详情 -->
    <el-drawer v-model="detailOpen" size="900px" :with-header="false">
      <div v-if="current" style="padding: 4px 6px">
        <div class="detail-hero">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; gap: 20px">
            <div>
              <h3>{{ current.appName }}</h3>
              <div class="sub mono">{{ current.appKey }}</div>
              <div style="margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap">
                <el-tag size="small" effect="plain">{{ authModeLabel(current.authMode) }}</el-tag>
                <el-tag size="small" :type="current.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ current.status === 'ACTIVE' ? '启用' : '停用' }}
                </el-tag>
                <el-tag size="small" type="warning" effect="plain" v-if="current.sandboxEnabled">沙盒已开</el-tag>
                <el-tag size="small" effect="plain">{{ current.grantedApiCount }} 个 API</el-tag>
              </div>
            </div>
            <div style="display: flex; gap: 8px">
              <el-button type="danger" plain @click="removeApp">删除应用</el-button>
              <el-button @click="detailOpen = false">关闭</el-button>
            </div>
          </div>
        </div>

        <el-tabs v-model="detailTab">
          <el-tab-pane label="概览" name="overview">
            <div class="card" style="box-shadow: none">
              <h3 class="card-title"><span class="dot"></span>基本配置</h3>
              <el-form label-width="110px">
                <el-form-item label="应用名称"><el-input v-model="editForm.appName" /></el-form-item>
                <el-form-item label="请求校验">
                  <el-select v-model="editForm.authMode" style="width: 100%">
                    <el-option label="OAuth2 + HMAC（令牌 + 签名，双凭证）" value="OAUTH2_HMAC" />
                    <el-option label="仅 AppKey + HMAC（不要令牌）" value="APPKEY_HMAC" />
                    <el-option label="仅 OAuth2 Token（不要签名）" value="OAUTH2_ONLY" />
                  </el-select>
                  <div class="hint" style="line-height: 1.6">决定每次请求要带什么，与下面的「令牌获取方式」是两件事。</div>
                </el-form-item>
                <el-form-item label="令牌获取">
                  <el-checkbox-group v-model="editForm.grantTypes">
                    <el-checkbox value="client_credentials">client_credentials（机器身份）</el-checkbox>
                    <el-checkbox value="authorization_code">authorization_code（用户身份）</el-checkbox>
                    <el-checkbox value="refresh_token">refresh_token（续期）</el-checkbox>
                  </el-checkbox-group>
                  <div class="hint" style="line-height: 1.6">
                    只有开通 authorization_code，第三方才能引导用户授权，调用标注「需用户授权」的接口。
                  </div>
                </el-form-item>
                <el-form-item label="回调地址" v-if="editForm.grantTypes?.includes('authorization_code')">
                  <el-input v-model="editForm.redirectUrisText" type="textarea" :rows="2"
                            placeholder="每行一个，如 https://third.example.com/oauth/cb" />
                  <div class="hint">精确匹配、不支持通配；换 code 时的 redirect_uri 必须与这里完全一致。</div>
                </el-form-item>
                <el-form-item label="Scopes">
                  <el-select v-model="editForm.scopes" multiple style="width: 100%">
                    <el-option label="read（只读）" value="read" />
                    <el-option label="write（写操作）" value="write" />
                  </el-select>
                </el-form-item>
                <el-form-item label="IP 白名单">
                  <el-input v-model="editForm.ipWhitelist" placeholder="逗号分隔；* 表示不限制" />
                </el-form-item>
                <el-row :gutter="12">
                  <el-col :span="12">
                    <el-form-item label="QPS 限制"><el-input-number v-model="editForm.qpsLimit" :min="1" /></el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="日配额"><el-input-number v-model="editForm.dayLimit" :min="1" /></el-form-item>
                  </el-col>
                </el-row>
                <el-form-item label="启用沙盒"><el-switch v-model="editForm.sandbox" /></el-form-item>
                <el-form-item label="状态">
                  <el-radio-group v-model="editForm.status">
                    <el-radio value="ACTIVE">启用</el-radio>
                    <el-radio value="DISABLED">停用</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-form>
              <div style="display: flex; gap: 10px">
                <el-button type="primary" @click="saveApp">保存配置</el-button>
                <el-button type="warning" plain @click="rotate">轮换密钥</el-button>
              </div>
              <p class="hint" style="margin-top: 10px">
                轮换后旧密钥立即失效；新密钥只显示一次，请通知第三方及时替换。
              </p>
            </div>
          </el-tab-pane>

          <el-tab-pane label="API 授权" name="apis">
            <div class="card" style="box-shadow: none">
              <div class="grant-summary">
                <div>
                  <div class="grant-count">{{ granted.length }}<span> / {{ catalog.length }}</span></div>
                  <div class="hint">个开放接口已授权给该应用</div>
                </div>
                <div class="grant-note">
                  勾选后该应用才能调用；未授权的接口返回 <b>403 API_NOT_GRANTED</b>。
                  未勾选不影响已签发的 AppKey，只影响可访问范围。
                </div>
              </div>
              <div class="toolbar">
                <span class="hint">按接口分组勾选，编码只用于对接文档。</span>
                <div class="spacer"></div>
                <el-button @click="toggleAllApis">{{ allGranted ? '全部取消' : '全部授权' }}</el-button>
                <el-button type="primary" @click="saveApis">保存授权</el-button>
              </div>
              <div v-for="group in apiGroups" :key="group.name" class="perm-group">
                <div class="perm-group-head">
                  <div><b>{{ group.name }}</b><span class="hint" style="margin-left: 8px">{{ group.items.length }} 个接口</span></div>
                  <el-checkbox :model-value="group.items.filter((i:any) => !blockedApi(i)).every((i:any) => granted.includes(i.code))"
                               @change="toggleGroup(group, $event)">全选本组</el-checkbox>
                </div>
                <div style="padding: 6px 14px">
                  <div v-for="item in group.items" :key="item.code" class="api-item"
                       :class="{ blocked: blockedApi(item) }">
                    <el-tooltip :disabled="!blockedApi(item)" placement="top"
                                content="该接口必须代表真实用户，请先在「概览」里为应用开通 authorization_code">
                      <el-checkbox :model-value="granted.includes(item.code)" :disabled="blockedApi(item)"
                                   @change="toggleApi(item.code, $event)" />
                    </el-tooltip>
                    <span class="method" :class="item.method">{{ item.method }}</span>
                    <div style="flex: 1">
                      <div style="font-size: 13px; font-weight: 600">
                        {{ item.name }}
                        <el-tag v-if="item.subject === 'USER'" size="small" type="warning" effect="plain"
                                style="margin-left: 6px">需用户授权</el-tag>
                        <el-tag v-else-if="item.subject === 'ANY'" size="small" effect="plain"
                                style="margin-left: 6px">两种身份皆可</el-tag>
                        <el-tag v-if="item.source === 'MANUAL'" size="small" type="info" effect="plain"
                                style="margin-left: 6px">手工登记</el-tag>
                      </div>
                      <div class="mono hint">{{ item.path }} · {{ item.code }}</div>
                      <div class="hint">{{ item.description }}</div>
                      <div class="hint" v-if="item.requiredPermission">
                        还需该用户拥有权限：<span class="code-chip">{{ item.requiredPermission }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="调用日志" name="logs">
            <div class="toolbar">
              <span class="hint">最近 100 次调用；点击行查看完整请求信息。</span>
              <div class="spacer"></div>
              <el-button size="small" :icon="Refresh" @click="loadLogs">刷新</el-button>
            </div>
            <el-table :data="logs" size="small" @row-click="openLogDetail" row-class-name="clickable">
              <el-table-column label="时间" width="165">
                <template #default="{ row }">{{ fmt(row.created_at) }}</template>
              </el-table-column>
              <el-table-column label="调用方" width="150">
                <template #default="{ row }">{{ row.app_name || current.appName }}</template>
              </el-table-column>
              <el-table-column label="API" min-width="180">
                <template #default="{ row }">
                  <span class="code-chip">{{ row.api_code || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="path" label="路径" min-width="180" show-overflow-tooltip />
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.status < 400 ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="80">
                <template #default="{ row }">{{ row.latency_ms }}ms</template>
              </el-table-column>
              <el-table-column prop="client_ip" label="IP" width="130" />
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="用量" name="usage">
            <div class="card" style="box-shadow: none">
              <h3 class="card-title"><span class="dot"></span>近 7 天调用</h3>
              <el-table :data="usage" size="small">
                <el-table-column prop="day" label="日期" width="150" />
                <el-table-column prop="calls" label="调用次数" width="120" />
                <el-table-column prop="errors" label="错误数" width="100" />
                <el-table-column label="占比">
                  <template #default="{ row }">
                    <div class="usage-bar"><i :style="{ width: usageWidth(row.calls) }"></i></div>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!usage.length" description="暂无调用数据" :image-size="70" />
            </div>
          </el-tab-pane>

          <el-tab-pane label="沙盒测试" name="sandbox">
            <div class="card" style="box-shadow: none">
              <el-alert type="info" :closable="false" show-icon
                        title="沙盒请求带 X-Sandbox: true，使用独立租户与配额，不写生产数据。" />
              <el-form label-width="80px" style="margin-top: 16px">
                <el-form-item label="方法">
                  <el-select v-model="sandbox.method" style="width: 140px">
                    <el-option label="GET" value="GET" />
                    <el-option label="POST" value="POST" />
                  </el-select>
                </el-form-item>
                <el-form-item label="路径">
                  <el-select v-model="sandbox.path" style="width: 100%">
                    <el-option v-for="item in catalog" :key="item.code" :label="`${item.method} ${item.path}`" :value="item.path" />
                  </el-select>
                </el-form-item>
                <el-form-item label="请求体">
                  <el-input v-model="sandbox.body" type="textarea" :rows="3" placeholder='{"orderId":"A001"}' />
                </el-form-item>
              </el-form>
              <el-button type="primary" @click="runSandbox">生成签名并自检</el-button>
              <div v-if="sandboxResult" class="detail-hero" style="margin-top: 16px">
                <div style="display: flex; align-items: center; gap: 10px">
                  <b>签名自检</b>
                  <el-tag :type="sandboxResult.signatureVerified ? 'success' : 'danger'" size="small">
                    {{ sandboxResult.signatureVerified ? '通过' : '失败' }}
                  </el-tag>
                </div>
                <div class="hint" style="margin-top: 10px">待签串</div>
                <pre class="json">{{ sandboxResult.canonical }}</pre>
                <div class="hint" style="margin-top: 10px">请求头</div>
                <pre class="json">{{ JSON.stringify(sandboxResult.headers, null, 2) }}</pre>
                <div class="hint" style="margin-top: 10px">curl</div>
                <pre class="json">{{ sandboxResult.curl }}</pre>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="对接说明" name="guide">
            <div class="card" style="box-shadow: none">
              <el-alert type="info" :closable="false" show-icon
                        title="把这一页发给第三方开发者即可：签名串写法、令牌获取、错误码都在这里。" />
              <div class="guide-step">
                <h3 class="card-title"><span class="dot"></span>第一步：换 access_token（仅 OAuth2 模式需要）</h3>
                <p class="hint">
                  当前应用认证模式：<b>{{ authModeLabel(current.authMode) }}</b>。
                  <span v-if="current.authMode === 'APPKEY_HMAC'">该模式不需要令牌，直接进入第二步。</span>
                  <span v-else>用 AppKey + ClientSecret 换 2 小时有效期的令牌。</span>
                </p>
                <pre class="json">{{ docToken }}</pre>
              </div>
              <div class="guide-step" v-if="current.grantTypes?.includes('authorization_code')">
                <h3 class="card-title"><span class="dot"></span>需要「代表用户」时：授权码流程</h3>
                <p class="hint">
                  上面换到的是机器令牌，没有用户身份，只能调 CLIENT 类型的接口。
                  要调标注「需用户授权」的接口，必须走下面的流程，拿到带用户身份的令牌；
                  调用时服务端还会再校验该用户自己是否有对应权限点。
                </p>
                <pre class="json">{{ docAuthorize }}</pre>
              </div>
              <div class="guide-step">
                <h3 class="card-title"><span class="dot"></span>第二步：拼待签串并计算签名</h3>
                <p class="hint">
                  七个部分用换行符 \n 连接：方法、路径、查询串（无则空串）、时间戳、随机串、请求体 SHA-256。
                  用 SigningSecret 做 HMAC-SHA256 后 Base64。时间戳与服务器相差不超过 5 分钟，随机串 10 分钟内不可重复。
                </p>
                <pre class="json">{{ docCanonical }}</pre>
              </div>
              <div class="guide-step">
                <h3 class="card-title"><span class="dot"></span>第三步：带齐请求头调用</h3>
                <pre class="json">{{ docCurl }}</pre>
              </div>
              <h3 class="card-title" style="margin-top: 18px"><span class="dot"></span>常见错误码</h3>
              <el-table :data="ERROR_CODES" size="small">
                <el-table-column prop="code" label="错误码" width="220">
                  <template #default="{ row }"><span class="code-chip">{{ row.code }}</span></template>
                </el-table-column>
                <el-table-column prop="status" label="HTTP" width="80" />
                <el-table-column prop="hint" label="含义与处理" />
              </el-table>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <!-- 创建应用 -->
    <el-dialog v-model="createOpen" title="创建开放应用" width="560px">
      <el-form label-width="100px">
        <el-form-item label="应用名称"><el-input v-model="form.appName" placeholder="第三方系统名称" /></el-form-item>
        <el-form-item label="请求校验">
          <el-select v-model="form.authMode" style="width: 100%">
            <el-option label="OAuth2 + HMAC（令牌 + 签名，双凭证）" value="OAUTH2_HMAC" />
            <el-option label="仅 AppKey + HMAC（不要令牌）" value="APPKEY_HMAC" />
            <el-option label="仅 OAuth2 Token（不要签名）" value="OAUTH2_ONLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="令牌获取">
          <el-checkbox-group v-model="form.grantTypes">
            <el-checkbox value="client_credentials">client_credentials（机器身份）</el-checkbox>
            <el-checkbox value="authorization_code">authorization_code（用户身份）</el-checkbox>
            <el-checkbox value="refresh_token">refresh_token（续期）</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="回调地址" v-if="form.grantTypes?.includes('authorization_code')">
          <el-input v-model="form.redirectUrisText" type="textarea" :rows="2"
                    placeholder="每行一个，如 https://third.example.com/oauth/cb" />
        </el-form-item>
        <el-form-item label="Scopes">
          <el-select v-model="form.scopes" multiple style="width: 100%">
            <el-option label="read" value="read" />
            <el-option label="write" value="write" />
          </el-select>
        </el-form-item>
        <el-form-item label="IP 白名单"><el-input v-model="form.ipWhitelist" placeholder="* 表示不限制" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="QPS"><el-input-number v-model="form.qpsLimit" :min="1" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="日配额"><el-input-number v-model="form.dayLimit" :min="1" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="启用沙盒"><el-switch v-model="form.sandbox" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" @click="create">创建并生成密钥</el-button>
      </template>
    </el-dialog>

    <!-- 密钥只显示一次 -->
    <el-dialog v-model="secretOpen" title="应用凭证（只显示一次）" width="620px">
      <el-alert type="warning" :closable="false" show-icon title="请立即保存。关闭后无法再次查看，只能轮换。" />
      <div class="card" style="margin-top: 14px; box-shadow: none">
        <p><b>AppKey</b>：<span class="mono">{{ secrets.appKey }}</span></p>
        <p><b>ClientSecret</b>（换取 token）：<span class="mono">{{ secrets.clientSecret }}</span></p>
        <p><b>SigningSecret</b>（请求签名）：<span class="mono">{{ secrets.signingSecret }}</span></p>
      </div>
      <template #footer>
        <el-button @click="secretOpen = false">我已保存</el-button>
      </template>
    </el-dialog>

    <!-- 日志详情 -->
    <el-dialog v-model="logDetailOpen" title="调用详情" width="680px">
      <dl class="kv" v-if="logDetail">
        <dt>调用方</dt><dd>{{ logDetail.app_name || '—' }}（{{ logDetail.app_key }}）</dd>
        <dt>API</dt><dd><span class="code-chip">{{ logDetail.api_code || '—' }}</span></dd>
        <dt>方法与路径</dt><dd><span class="code-chip">{{ logDetail.method }}</span> {{ logDetail.path }}</dd>
        <dt>状态</dt><dd>{{ logDetail.status }} · {{ logDetail.result_code }}</dd>
        <dt>耗时</dt><dd>{{ logDetail.latency_ms }} ms</dd>
        <dt>来源 IP</dt><dd>{{ logDetail.client_ip }}</dd>
        <dt>User-Agent</dt><dd class="hint">{{ logDetail.user_agent || '—' }}</dd>
        <dt>沙盒</dt><dd>{{ logDetail.sandbox ? '是' : '否' }}</dd>
        <dt>TraceId</dt><dd class="mono">{{ logDetail.trace_id }}</dd>
        <dt>时间</dt><dd>{{ fmt(logDetail.created_at) }}</dd>
      </dl>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'

const apps = ref<any[]>([])
const catalog = ref<any[]>([])
const loading = ref(false)
const createOpen = ref(false)
const secretOpen = ref(false)
const detailOpen = ref(false)
const logDetailOpen = ref(false)
const current = ref<any>(null)
const detailTab = ref('overview')
const logs = ref<any[]>([])
const usage = ref<any[]>([])
const logDetail = ref<any>(null)
const granted = ref<string[]>([])
const secrets = reactive<any>({})
const sandboxResult = ref<any>(null)
const sandbox = reactive({ method: 'GET', path: '/openapi/v1/ping', body: '' })
const form = reactive<any>({
  appName: '', authMode: 'OAUTH2_HMAC', grantTypes: ['client_credentials'],
  scopes: ['read', 'write'], ipWhitelist: '*', qpsLimit: 10, dayLimit: 10000,
  sandbox: true, redirectUrisText: ''
})
const editForm = reactive<any>({})

const AUTH_MODES: Record<string, string> = {
  OAUTH2_HMAC: 'OAuth2 + HMAC', APPKEY_HMAC: 'AppKey + HMAC', OAUTH2_ONLY: 'OAuth2 Only'
}
const GRANT_LABELS: Record<string, string> = {
  client_credentials: '机器令牌', authorization_code: '用户授权', refresh_token: '可续期'
}
function authModeLabel(mode: string) { return AUTH_MODES[mode] || mode }
function grantLabel(grants: string[]) {
  if (!grants?.length) return '—'
  return grants.map((item) => GRANT_LABELS[item] || item).join(' · ')
}

/** 需要用户主体、但应用还没开通 authorization_code：勾了也调不通，直接置灰。 */
function blockedApi(item: any) {
  return item.subject === 'USER' && !current.value?.grantTypes?.includes('authorization_code')
}

function splitLines(text: string) {
  return (text || '').split(/[\r\n,]+/).map((item) => item.trim()).filter(Boolean)
}

/** 日配额动辄十万，直接显示数字在表格里会换行 */
function fmtDay(limit: number) {
  if (limit == null) return '—'
  return limit >= 10000 ? `${Math.round(limit / 10000)} 万` : String(limit)
}

const BASE_URL = typeof location !== 'undefined' ? location.origin : 'https://your-domain'

const EMPTY_SHA = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

const docToken = computed(() => {
  const key = current.value?.appKey || 'ak_xxx'
  return [
    `curl -X POST ${BASE_URL}/openapi/v1/oauth/token \\`,
    '  -H "Content-Type: application/json" \\',
    `  -d '{"appKey":"${key}","clientSecret":"<ClientSecret>","grantType":"client_credentials"}'`,
    '',
    '# 返回 { "access_token": "...", "expires_in": 7200 }',
  ].join('\n')
})

const docAuthorize = computed(() => {
  const key = current.value?.appKey || 'ak_xxx'
  const redirect = current.value?.redirectUris?.[0] || 'https://third.example.com/oauth/cb'
  return [
    '# 1. 把浏览器跳到授权页；用户没登录会先跳到登录页，登录后自动回到授权页',
    `${BASE_URL}/#/oauth/authorize?client_id=${key}`,
    `&redirect_uri=${encodeURIComponent(redirect)}&scope=read,write&state=xyz`,
    '',
    '# 2. 用户点「同意」后，浏览器回到你的回调地址',
    `${redirect}?code=<授权码>&state=xyz`,
    '',
    '# 3. 你的后端用 code 换令牌（5 分钟内有效，且只能换一次）',
    `curl -X POST ${BASE_URL}/openapi/v1/oauth/token \\`,
    '  -H "Content-Type: application/json" \\',
    `  -d '{"clientId":"${key}","clientSecret":"<ClientSecret>",`
      + '"grantType":"authorization_code","code":"<授权码>",'
      + `"redirectUri":"${redirect}"}'`,
    '',
    '# 返回里多一个 refresh_token（默认 30 天，可配置）；换到的 access_token 带 uid，代表该用户',
    '# 用 refresh_token 续期时 grantType 传 refresh_token，字段名 refreshToken / refresh_token 都认',
  ].join('\n')
})

const docCanonical = computed(() => [
  'GET',
  '/openapi/v1/ping',
  '',                                  // 查询串，没有就留空行
  '1730000000',                        // X-Timestamp
  '6f1c9a2d4b7e45c8a0d3f6e9b2c5d8a1',  // X-Nonce，每次不同
  EMPTY_SHA,                           // 请求体的 SHA-256，GET 用空串的哈希
  '',
  '# 签名 = base64(hmac_sha256(SigningSecret, 上面六行))',
].join('\n'))

const docCurl = computed(() => {
  const key = current.value?.appKey || 'ak_xxx'
  const lines = [
    `curl -X GET ${BASE_URL}/openapi/v1/ping \\`,
    `  -H "X-App-Key: ${key}" \\`,
    '  -H "X-Timestamp: 1730000000" \\',
    '  -H "X-Nonce: 6f1c9a2d4b7e45c8a0d3f6e9b2c5d8a1" \\',
    '  -H "X-Signature: <第二步算出的签名>"',
  ]
  if (current.value?.authMode !== 'APPKEY_HMAC') {
    lines[lines.length - 1] += ' \\'
    lines.push('  -H "Authorization: Bearer <access_token>"')
  }
  lines.push('', '# 沙盒：再加 -H "X-Sandbox: true"，走独立租户与配额')
  return lines.join('\n')
})

const ERROR_CODES = [
  { code: 'SIGNATURE_HEADERS_MISSING', status: 401, hint: '缺 X-Timestamp / X-Nonce / X-Signature' },
  { code: 'APP_KEY_MISSING', status: 401, hint: '缺 X-App-Key' },
  { code: 'APP_NOT_FOUND', status: 401, hint: 'AppKey 不存在或已被删除' },
  { code: 'APP_DISABLED', status: 403, hint: '应用已停用，请在控制台启用' },
  { code: 'IP_NOT_ALLOWED', status: 403, hint: '来源 IP 不在白名单' },
  { code: 'TIMESTAMP_EXPIRED', status: 401, hint: '时间戳超出 5 分钟窗口，校准服务器时间' },
  { code: 'SIGNATURE_INVALID', status: 401, hint: '待签串拼错，注意空查询串也要占一行' },
  { code: 'NONCE_REPLAYED', status: 409, hint: '同一随机串重复使用，每次请求重新生成' },
  { code: 'TOKEN_REQUIRED', status: 401, hint: '该认证模式要求带 Bearer 令牌' },
  { code: 'TOKEN_INVALID', status: 401, hint: '令牌签名不对或已过期，重新换一次' },
  { code: 'TOKEN_BINDING_MISMATCH', status: 401, hint: '令牌不属于当前 AppKey' },
  { code: 'SUBJECT_REQUIRED', status: 403, hint: '该接口必须代表真实用户，机器令牌不能调' },
  { code: 'USER_NOT_FOUND', status: 401, hint: '授权用户不存在或已停用' },
  { code: 'USER_PERMISSION_DENIED', status: 403, hint: '被代理用户自己没有该业务权限' },
  { code: 'TENANT_MISMATCH', status: 403, hint: '用户与应用不属于同一租户' },
  { code: 'SCOPE_DENIED', status: 403, hint: '令牌缺所需 scope' },
  { code: 'QPS_LIMITED', status: 429, hint: '超过每秒请求数限制' },
  { code: 'DAILY_QUOTA_EXCEEDED', status: 429, hint: '当日调用配额用尽' },
  { code: 'API_NOT_FOUND', status: 404, hint: '路径不在开放 API 目录中' },
  { code: 'API_NOT_GRANTED', status: 403, hint: '该应用未被授权调用此接口' },
  { code: 'SANDBOX_DISABLED', status: 403, hint: '应用或平台未开启沙盒' },
  { code: 'SANDBOX_NOT_SUPPORTED', status: 403, hint: '该接口声明了不支持沙盒' },
]
function fmt(value: any) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }

const apiGroups = computed(() => {
  const groups = new Map<string, any[]>()
  for (const item of catalog.value) {
    const key = item.groupName || '其他'
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(item)
  }
  return Array.from(groups.entries()).map(([name, items]) => ({ name, items }))
})
/** 全选口径要排除置灰项，否则点「全部授权」会把调不通的接口也提交上去。 */
const allGranted = computed(() => {
  const selectable = catalog.value.filter((item: any) => !blockedApi(item))
  return selectable.length > 0 && selectable.every((item: any) => granted.value.includes(item.code))
})

/** 列表上的「n / m」按启用中的接口算，停用的不该进入分母。 */
const activeCatalog = computed(() => catalog.value.filter((item: any) => item.enabled !== false))

function usageWidth(calls: number) {
  const max = Math.max(...usage.value.map((item) => Number(item.calls) || 0), 1)
  return Math.round((Number(calls) / max) * 100) + '%'
}

async function load() {
  loading.value = true
  try {
    const [appRes, catalogRes] = await Promise.all([
      http.get('/api/platform/apps'),
      http.get('/api/platform/apis')
    ])
    apps.value = appRes.data
    // 目录接口返回 { items, groups }；items 里含已停用的，用于列表计数时再过滤
    catalog.value = catalogRes.data.items || catalogRes.data || []
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    appName: '', authMode: 'OAUTH2_HMAC', grantTypes: ['client_credentials'],
    scopes: ['read', 'write'], ipWhitelist: '*', qpsLimit: 10, dayLimit: 10000,
    sandbox: true, redirectUrisText: ''
  })
  createOpen.value = true
}

async function create() {
  if (!form.appName) {
    ElMessage.warning('请填写应用名称')
    return
  }
  try {
    const { redirectUrisText, ...rest } = form
    const { data } = await http.post('/api/platform/apps', {
      ...rest, redirectUris: splitLines(redirectUrisText)
    })
    showSecrets({ appKey: data.app.appKey, clientSecret: data.clientSecret, signingSecret: data.signingSecret })
    createOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function showSecrets(payload: any) {
  secrets.appKey = payload.appKey
  secrets.clientSecret = payload.clientSecret
  secrets.signingSecret = payload.signingSecret
  secretOpen.value = true
}

async function openDetail(row: any, tab: string) {
  current.value = row
  detailTab.value = tab
  Object.assign(editForm, {
    appName: row.appName, authMode: row.authMode,
    grantTypes: [...(row.grantTypes || ['client_credentials'])],
    scopes: [...row.scopes],
    ipWhitelist: row.ipWhitelist, qpsLimit: row.qpsLimit, dayLimit: row.dayLimit,
    status: row.status, sandbox: row.sandboxEnabled,
    redirectUrisText: (row.redirectUris || []).join('\n')
  })
  detailOpen.value = true
  await Promise.allSettled([loadApis(), loadLogs(), loadUsage()])
}

async function loadApis() {
  const { data } = await http.get(`/api/platform/apps/${current.value.id}/apis`)
  granted.value = data.granted || []
  if (data.catalog?.length) catalog.value = data.catalog
}

function toggleApi(code: string, checked: boolean) {
  granted.value = checked ? Array.from(new Set([...granted.value, code])) : granted.value.filter((item) => item !== code)
}
function toggleGroup(group: any, checked: boolean) {
  const codes = group.items.filter((item: any) => !blockedApi(item)).map((item: any) => item.code)
  granted.value = checked
    ? Array.from(new Set([...granted.value, ...codes]))
    : granted.value.filter((code) => !codes.includes(code))
}
function toggleAllApis() {
  granted.value = allGranted.value ? []
    : catalog.value.filter((item: any) => !blockedApi(item)).map((item: any) => item.code)
}

async function saveApis() {
  try {
    await http.put(`/api/platform/apps/${current.value.id}/apis`, { codes: granted.value })
    ElMessage.success('API 授权已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function saveApp() {
  try {
    await http.put(`/api/platform/apps/${current.value.id}`, {
      appName: editForm.appName, authMode: editForm.authMode,
      grantTypes: editForm.grantTypes, scopes: editForm.scopes,
      ipWhitelist: editForm.ipWhitelist, qpsLimit: editForm.qpsLimit, dayLimit: editForm.dayLimit,
      status: editForm.status, sandbox: editForm.sandbox,
      redirectUris: splitLines(editForm.redirectUrisText)
    })
    ElMessage.success('已保存')
    await load()
    // 本地 current 也要跟着变，否则授权树的置灰判据还停留在旧值
    current.value = apps.value.find((item) => item.id === current.value.id) || current.value
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function rotate() {
  try {
    await ElMessageBox.confirm(`轮换 ${current.value.appName} 的密钥？旧密钥立即失效。`, '轮换密钥', { type: 'warning' })
    const { data } = await http.post(`/api/platform/apps/${current.value.id}/rotate`, {})
    showSecrets(data)
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function removeApp() {
  try {
    await ElMessageBox.confirm(
      `删除后 ${current.value.appName} 的 AppKey 立即失效，调用日志仍会保留可追溯。`,
      '删除应用', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
    await http.delete(`/api/platform/apps/${current.value.id}`)
    ElMessage.success('已删除')
    detailOpen.value = false
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function loadLogs() {
  try {
    const { data } = await http.get(`/api/platform/apps/${current.value.id}/logs`, { params: { limit: 100 } })
    logs.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function loadUsage() {
  try {
    const { data } = await http.get(`/api/platform/apps/${current.value.id}/usage`, { params: { days: 7 } })
    usage.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function openLogDetail(row: any) {
  try {
    const { data } = await http.get(`/api/platform/apps/${current.value.id}/logs/${row.id}`)
    logDetail.value = data
    logDetailOpen.value = true
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function runSandbox() {
  try {
    let body: any = null
    if (sandbox.body.trim()) body = JSON.parse(sandbox.body)
    const { data } = await http.post(`/api/platform/apps/${current.value.id}/sandbox-test`, {
      method: sandbox.method, path: sandbox.path, body
    })
    sandboxResult.value = data
  } catch (error: any) {
    ElMessage.error(error instanceof SyntaxError ? '请求体不是合法 JSON' : errorText(error))
  }
}

onMounted(load)
</script>

<style scoped>
:deep(.el-table__row.clickable) { cursor: pointer; }
.api-count { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; }
.link-text { color: var(--brand, #17564A); font-size: 12px; }
.link-text:hover { text-decoration: underline; }
.grant-summary {
  display: flex; align-items: center; gap: 28px; padding: 16px 18px; margin-bottom: 8px;
  background: #F4F8F6; border: 1px solid #DCE7E1; border-radius: 12px;
}
.grant-summary > div:first-child { min-width: 132px; }
.grant-count { font-size: 26px; font-weight: 700; color: #17564A; line-height: 1.1; }
.grant-count span { font-size: 15px; font-weight: 500; color: #6B7671; }
.grant-note { flex: 1; font-size: 13px; color: #4B5550; line-height: 1.7; }
.guide-step { margin-top: 20px; }
.guide-step .json { margin-top: 8px; }
.api-item.blocked { opacity: .5; }
.api-item.blocked .method { filter: grayscale(1); }
</style>
