import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  checkSqlitePath,
  createAlias,
  getAlias,
  listServerDirectory,
  updateAlias,
  type CreateAliasRequest,
  type SqlitePathCheckResult,
} from '../../api/aliases';
import { getSchemaCatalog } from '../../api/workspace';
import { getDrivers } from '../../api/drivers';
import { queryClient } from '../../api/queryClient';
import './alias-home.css';
import { Button } from '../../ui/Button';
import { Select } from '../../ui/Select';

const initialForm: CreateAliasRequest = {
  name: '',
  accessMode: 'jdbc',
  dbType: 'mysql',
  driverRef: '',
  jdbcUrl: '',
  database: '',
  username: '',
  secretRef: '',
  secretValue: '',
  description: '',
  defaultSchema: '',
  readonly: false,
};

/** `editName` 为空即新建；非空时回填该别名并改用 PATCH 提交。 */
export function AliasAddPage({ onDone, editName }: { onDone: () => void; editName?: string }) {
  const [form, setForm] = useState(initialForm);
  /** 保存前的 sqlite 路径预检结果；路径一变就作废，避免拿旧结果掩盖新路径没测过。 */
  const [sqliteCheck, setSqliteCheck] = useState<'checking' | SqlitePathCheckResult | null>(null);
  /** 是否展开服务端目录选择器；路径输入框本身保留，这只是个辅助入口 */
  const [browsingPath, setBrowsingPath] = useState(false);
  const detail = useQuery({
    queryKey: ['alias', editName],
    queryFn: ({ signal }) => getAlias(editName!, signal),
    enabled: !!editName,
  });

  useEffect(() => {
    if (!detail.data) return;
    const d = detail.data;
    setForm({
      name: d.name,
      accessMode: d.accessMode === 'yearning' ? 'yearning' : 'jdbc',
      dbType: d.dbType ?? 'mysql',
      driverRef: d.driverRef ?? '',
      jdbcUrl: d.jdbcUrl ?? '',
      database: d.database ?? '',
      username: d.username ?? '',
      // 后端只回 scheme 掩码，不能当成可提交的值回填——留空表示“不改 secretRef”。
      secretRef: '',
      secretValue: '',
      description: d.description ?? '',
      defaultSchema: d.defaultSchema ?? '',
      readonly: d.readonly,
      yearningHost: d.yearningHost ?? '',
      yearningIdc: d.yearningIdc ?? '',
      yearningDatabase: d.yearningDatabase ?? '',
    });
  }, [detail.data]);

  const create = useMutation({
    mutationFn: createAlias,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['aliases'] });
      onDone();
    },
  });
  const edit = useMutation({
    // extra 用来带一次性算出来的字段（sqlite 的 createIfMissing）——不是持久 state，
    // 不该塞进 form，塞进去反而要操心「换路径以后要不要清掉」这种多余状态。
    mutationFn: (extra?: Partial<CreateAliasRequest>) => {
      const { name, secretValue, secretRef, ...rest } = { ...form, ...extra };
      void name;
      void secretValue;
      return updateAlias(editName!, secretRef ? { ...rest, secretRef } : rest);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['aliases'] });
      queryClient.invalidateQueries({ queryKey: ['alias', editName] });
      onDone();
    },
  });
  const pending = editName ? edit.isPending : create.isPending;
  const failure = editName ? edit.error : create.error;

  const update = (field: keyof CreateAliasRequest, value: string | boolean) => {
    setForm((current) => ({ ...current, [field]: value }));
  };

  const isJdbc = form.accessMode === 'jdbc';
  const isSqlite = isJdbc && form.dbType === 'sqlite';

  /** 换库类型时清掉上一个类型留下的连接字段，sqlite 和其他库互不共用一套。 */
  const changeDbType = (dbType: string) => {
    setForm((current) => ({ ...current, dbType, jdbcUrl: '', database: '', driverRef: '', username: '' }));
    setSqliteCheck(null);
    setBrowsingPath(false);
  };

  /** 选择器点中一个文件：回填输入框、走已有的路径预检失效逻辑、收起选择器。 */
  const selectSqlitePath = (path: string) => {
    update('database', path);
    setSqliteCheck(null);
    setBrowsingPath(false);
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    // sqlite 策略在真正连接时会拒绝一个不存在的文件（防止拼错路径被静默新建成空库），
    // 除非显式开了 createIfMissing。这里先问一遍文件系统，路径不存在就要用户当场确认，
    // 确认后把这个开关随保存一起带过去——只在这次提交里问过、不落进配置的话，
    // 下一次真正连接（测试、查询）会被同一个检查拦下来，等于白问。
    let sqliteCreateIfMissing: boolean | undefined;
    if (isSqlite) {
      setSqliteCheck('checking');
      let result: SqlitePathCheckResult;
      try {
        result = await checkSqlitePath(form.database ?? '');
      } catch (error) {
        // 路径测不了（比如格式非法）时不盲目保存——理由和文件不存在一样。
        setSqliteCheck({ exists: false, path: form.database ?? '', message: (error as Error).message });
        return;
      }
      setSqliteCheck(result);
      if (!result.exists) {
        if (!window.confirm(`${result.message}。\n\n确定新建吗？`)) {
          return;
        }
        sqliteCreateIfMissing = true;
      }
    }
    if (editName) {
      edit.mutate(isSqlite ? { sqliteCreateIfMissing: sqliteCreateIfMissing ?? false } : undefined);
      return;
    }
    create.mutate(
      isSqlite
        ? { ...form, sqliteCreateIfMissing }
        : { ...form, secretRef: form.secretRef || `keyring:${form.name}` },
    );
  };

  return (
    <main className="alias-add-page">
      <form className="alias-form" onSubmit={submit}>
        <header className="alias-form-header">
          <Button
            aria-label="返回数据源列表"
            title="返回数据源列表"
            onClick={onDone}
            size="sm"
            icon
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
            </svg>
          </Button>
          <h1>{editName ?? '新建数据源'}</h1>
          {editName && <span className="alias-form-tag">编辑</span>}
        </header>

        {failure && <p className="alias-form-error" role="alert">保存失败：{failure.message}</p>}
        {editName && detail.isLoading && <p className="alias-form-hint">加载中…</p>}

        <section className="alias-section">
          <h2>连接</h2>
          <div className="alias-grid-2">
            <label>
              名称
              <input
                required
                value={form.name}
                disabled={!!editName}
                onChange={(event) => update('name', event.target.value)}
                placeholder="app-dev"
              />
            </label>
            <label>
              访问方式
              <Select
                value={form.accessMode}
                onChange={(event) => update('accessMode', event.target.value as CreateAliasRequest['accessMode'])}
              >
                <option value="jdbc">JDBC</option>
                <option value="yearning">Yearning</option>
              </Select>
            </label>
          </div>

          {isJdbc ? (
            <>
              <div className="alias-grid-2">
                <label>
                  类型
                  <Select value={form.dbType} onChange={(event) => changeDbType(event.target.value)}>
                    <option value="mysql">MySQL</option>
                    <option value="oracle">Oracle</option>
                    <option value="postgresql">PostgreSQL</option>
                    <option value="clickhouse">ClickHouse</option>
                    <option value="sqlite">SQLite</option>
                  </Select>
                </label>
                {/* sqlite 驱动内置在 jar 里，不用像其他库那样选一个 settings.yaml 里配好的驱动 */}
                {!isSqlite && (
                  <DriverField
                    dbType={form.dbType ?? ''}
                    value={form.driverRef ?? ''}
                    onChange={(value) => update('driverRef', value)}
                  />
                )}
              </div>
              {isSqlite ? (
                <>
                  <label>
                    数据库文件路径
                    <div className="alias-path-row">
                      <input
                        required
                        className="alias-mono"
                        value={form.database ?? ''}
                        title="sql-cli 所在机器上的文件路径，不是你当前浏览器所在电脑的路径"
                        placeholder="D:/data/shop.db"
                        onChange={(event) => {
                          update('database', event.target.value);
                          setSqliteCheck(null);
                        }}
                      />
                      <Button
                        size="sm"
                        title="在 sql-cli 所在机器上浏览（不是当前浏览器所在电脑）"
                        onClick={() => setBrowsingPath((open) => !open)}
                      >
                        浏览…
                      </Button>
                    </div>
                    {sqliteCheck === 'checking' && <small className="alias-form-hint">测试连接中…</small>}
                    {sqliteCheck && sqliteCheck !== 'checking' && (
                      <small
                        className={sqliteCheck.exists ? 'alias-form-hint' : 'alias-form-warn'}
                        role="status"
                      >
                        {sqliteCheck.message}
                      </small>
                    )}
                  </label>
                  {browsingPath && (
                    <SqlitePathBrowser
                      initialPath={form.database}
                      onSelect={selectSqlitePath}
                      onClose={() => setBrowsingPath(false)}
                    />
                  )}
                </>
              ) : (
                <label>
                  JDBC URL
                  <input
                    required
                    className="alias-mono"
                    value={form.jdbcUrl}
                    onChange={(event) => update('jdbcUrl', event.target.value)}
                    placeholder="jdbc:mysql://127.0.0.1:3306/app"
                  />
                </label>
              )}
            </>
          ) : (
            <>
              <label>
                Yearning 地址
                <input
                  required
                  className="alias-mono"
                  value={form.yearningHost}
                  onChange={(event) => update('yearningHost', event.target.value)}
                  placeholder="https://yearning.example.com"
                />
              </label>
              <div className="alias-grid-2">
                <label>
                  IDC
                  <input required value={form.yearningIdc} onChange={(event) => update('yearningIdc', event.target.value)} />
                </label>
                <label>
                  数据库
                  <input required value={form.yearningDatabase} onChange={(event) => update('yearningDatabase', event.target.value)} />
                </label>
              </div>
            </>
          )}
        </section>

        {/* sqlite 是文件连接，没有账号密码这一层——整段不占版面，不是禁用后还留着空字段 */}
        {!isSqlite && (
          <section className="alias-section">
            <h2>认证</h2>
            <div className="alias-grid-2">
              {isJdbc && (
                <label>
                  用户名
                  <input required value={form.username} onChange={(event) => update('username', event.target.value)} />
                </label>
              )}
              <label>
                密码引用
                <input
                  className="alias-mono"
                  value={form.secretRef}
                  onChange={(event) => update('secretRef', event.target.value)}
                  placeholder={editName ? `${detail.data?.secretRef ?? '…'}（留空不改）` : `keyring:${form.name || '别名'}`}
                />
              </label>
              {!editName && (
                <label>
                  密码
                  <input
                    type="password"
                    autoComplete="new-password"
                    value={form.secretValue}
                    onChange={(event) => update('secretValue', event.target.value)}
                    placeholder="可留空"
                  />
                </label>
              )}
            </div>
          </section>
        )}

        <section className="alias-section">
          <h2>行为</h2>
          <div className="alias-grid-2">
            <DefaultSchemaField
              alias={editName}
              value={form.defaultSchema ?? ''}
              onChange={(value) => update('defaultSchema', value)}
            />
            <label>
              描述
              <input value={form.description} onChange={(event) => update('description', event.target.value)} />
            </label>
          </div>
          <label className="alias-switch">
            <input
              type="checkbox"
              checked={form.readonly}
              onChange={(event) => update('readonly', event.target.checked)}
            />
            <span className="alias-switch-track" aria-hidden="true" />
            <span>
              只读连接
              <small>拒绝一切写操作</small>
            </span>
          </label>
        </section>

        <div className="alias-form-actions">
          <Button onClick={onDone}>取消</Button>
          <Button type="submit" disabled={pending} variant="primary">
            {pending ? '保存中…' : '保存'}
          </Button>
        </div>
      </form>
    </main>
  );
}

/**
 * 默认 schema 选择。
 *
 * 能连上库就列出真实的 schema 供选择——手打 schema 名既不知道有哪些可选，也容易拼错。
 * 但新建别名时还没保存、连不上库，只能退回手工输入；已存在的别名连接失败时同理。
 */
function DefaultSchemaField({
  alias,
  value,
  onChange,
}: {
  alias?: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const catalog = useQuery({
    queryKey: ['schemas', 'catalog', alias],
    queryFn: ({ signal }) => getSchemaCatalog(alias!, signal),
    enabled: Boolean(alias),
    retry: false,
    staleTime: 60_000,
  });

  // 连不上或还没保存：退回输入框，不能因为列不出来就不让填
  if (!alias || catalog.isError) {
    return (
      <label>
        默认 schema
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={alias ? '无法读取 schema 列表，可手动填写' : '留空则用 URL 里的 schema'}
        />
      </label>
    );
  }

  if (catalog.isLoading) {
    return (
      <label>
        默认 schema
        <input value={value} disabled placeholder="正在读取 schema 列表…" />
      </label>
    );
  }

  const options = (catalog.data ?? []).filter((item) => !item.system);
  // 配置里存的值可能已经不在库里了（schema 被删或改名），仍要显示出来而不是静默丢掉
  const missing = value && !options.some((item) => item.name === value);

  return (
    <label>
      默认 schema
      <Select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">不指定（用 URL 里的 schema）</option>
        {missing && <option value={value}>{value}（数据库中已不存在）</option>}
        {options.map((item) => (
          <option key={item.name} value={item.name}>
            {item.name}
            {item.imported ? `（已导入 ${item.tableCount} 表）` : ''}
          </option>
        ))}
      </Select>
    </label>
  );
}

/**
 * 驱动选择。
 *
 * 从已配置的驱动里选，而不是手打名字——名字打错了要等到连接时才报「驱动未配置」。
 * 优先列出与当前数据库类型匹配的；类型不匹配的仍可选（有人会自定义命名），但排在后面并标注。
 */
function DriverField({
  dbType,
  value,
  onChange,
}: {
  dbType: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const drivers = useQuery({
    queryKey: ['drivers'],
    queryFn: ({ signal }) => getDrivers(signal),
    staleTime: 30_000,
    retry: false,
  });

  // 读不到驱动列表时退回输入框，不能因为列不出来就没法填
  if (drivers.isError) {
    return (
      <label>
        驱动
        <input
          required
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder="mysql8"
        />
      </label>
    );
  }

  const all = drivers.data ?? [];
  const matched = all.filter((item) => item.dbType === dbType);
  const others = all.filter((item) => item.dbType !== dbType);
  const missing = value && !all.some((item) => item.name === value);

  return (
    <label>
      驱动
      <Select required value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="" disabled>{drivers.isLoading ? '加载中…' : '请选择'}</option>
        {missing && <option value={value}>{value}（配置已不存在）</option>}
        {matched.map((item) => (
          <option key={item.name} value={item.name}>
            {item.name}
            {item.status === 'ok' ? '' : ' ⚠ jar 不可用'}
          </option>
        ))}
        {others.map((item) => (
          <option key={item.name} value={item.name}>
            {item.name}（{item.dbType}）
          </option>
        ))}
      </Select>
    </label>
  );
}

/**
 * sqlite 路径选择器：服务端目录浏览的展开面板。
 *
 * 没有抽成 `web/src/ui/` 里的通用弹层组件——目前只有这一个调用点，抽出来的 Dialog
 * 要解决焦点陷阱、ESC 关闭、点遮罩关闭、渲染到 portal 躲开裁剪这些问题，而这里
 * 一个都用不上（面板内联展开在表单里，没有遮罩层，不需要抢焦点）。真出现第二个
 * 需要弹层的场景时再抽，现在抽是为一个假设的需求预付成本。
 */
function SqlitePathBrowser({
  initialPath,
  onSelect,
  onClose,
}: {
  initialPath?: string;
  onSelect: (path: string) => void;
  onClose: () => void;
}) {
  const [path, setPath] = useState<string | undefined>(() => parentDirOf(initialPath));
  const listing = useQuery({
    queryKey: ['fs-list', path],
    queryFn: ({ signal }) => listServerDirectory(path, signal),
    retry: false,
  });

  return (
    <div className="alias-file-browser" role="group" aria-label="选择数据库文件">
      <div className="alias-file-browser-head">
        <Button size="sm" icon title="起点" aria-label="回到起点" onClick={() => setPath(undefined)}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M12 3 2 12h3v8h6v-6h2v6h6v-8h3L12 3z" />
          </svg>
        </Button>
        <Button
          size="sm"
          icon
          title="上一级"
          aria-label="上一级"
          disabled={!listing.data?.parent}
          onClick={() => setPath(listing.data?.parent ?? undefined)}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
          </svg>
        </Button>
        <span className="alias-file-browser-path">{listing.data?.path ?? '选择起点'}</span>
        <Button size="sm" icon title="关闭" aria-label="关闭选择器" onClick={onClose}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.3 19.71 2.88 18.3 9.17 12 2.88 5.71 4.3 4.29l6.29 6.3 6.29-6.3z" />
          </svg>
        </Button>
      </div>
      <p className="alias-form-hint">这里列的是 sql-cli 所在机器上的目录，不是当前浏览器所在电脑。</p>
      <ul className="alias-file-browser-list">
        {listing.isLoading && <li className="alias-file-browser-hint">加载中…</li>}
        {listing.isError && (
          <li className="alias-file-browser-hint" role="alert">{(listing.error as Error).message}</li>
        )}
        {listing.data && listing.data.entries.length === 0 && (
          <li className="alias-file-browser-hint">空目录</li>
        )}
        {listing.data?.entries.map((item) => (
          <li key={item.path}>
            <button
              type="button"
              className={item.dir ? 'alias-file-browser-entry is-dir' : 'alias-file-browser-entry'}
              onClick={() => (item.dir ? setPath(item.path) : onSelect(item.path))}
            >
              {item.name}
              {item.dir ? '/' : ''}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 从一个文件路径推出它所在的目录，作为选择器展开时的起点；推不出来就回退到根列表。 */
function parentDirOf(filePath?: string): string | undefined {
  if (!filePath) return undefined;
  const cut = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
  return cut > 0 ? filePath.slice(0, cut) : undefined;
}
