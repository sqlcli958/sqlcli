import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import type { UpdateAliasRequest } from '../../api/aliases';
import { deleteAlias, getAliases, setAliasSecret, testAlias, updateAlias } from '../../api/aliases';
import { queryClient } from '../../api/queryClient';
import type { AliasSummaryDto, AliasTestResultDto } from '../../types/api';
import { ActionIcon } from '../../ui/ActionIcon';
import { AliasAddPage } from '../alias-home/AliasAddPage';
import { DriverSettings } from './DriverSettings';
import './settings.css';
import { Button } from '../../ui/Button';
import { Menu, MenuItem } from '../../ui/Menu';

type Tab = 'sources' | 'drivers';

/**
 * 设置页。
 *
 * 两个标签：数据源和驱动。规则不在这里——它是一级页面，从导航进。
 *
 * 日常运维已经不需要离开 UI：建/改/删数据源、连接测试、设置密码、审批开关、
 * 驱动增删改与 jar 上传都在这两个标签里。CLI 侧只剩
 * `secret status / delete` 没有对应入口，列在页尾的「仅 CLI 可用」里，
 * 缺什么明写出来而不是假装没有。
 */
export function SettingsPage() {
  const [tab, setTab] = useState<Tab>('sources');
  /** null=列表；''=新建；其他=编辑该别名。 */
  const [editing, setEditing] = useState<string | null>(null);

  return (
    <div className="page settings">
      <header className="settings-head">
        <h1>设置</h1>
        <div className="settings-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'sources'}
            className={tab === 'sources' ? 'is-active' : ''}
            onClick={() => setTab('sources')}
          >
            数据源
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'drivers'}
            className={tab === 'drivers' ? 'is-active' : ''}
            onClick={() => setTab('drivers')}
          >
            驱动
          </button>
        </div>
      </header>

      {tab === 'sources' &&
        (editing !== null ? (
          <AliasAddPage key={editing} editName={editing || undefined} onDone={() => setEditing(null)} />
        ) : (
          <SourceSettings onAdd={() => setEditing('')} onEdit={setEditing} />
        ))}

      {tab === 'drivers' && <DriverSettings />}
    </div>
  );
}

function SourceSettings({ onAdd, onEdit }: { onAdd: () => void; onEdit: (name: string) => void }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });
  const [tested, setTested] = useState<Record<string, AliasTestResultDto | { success: false; message: string }>>({});
  const [testing, setTesting] = useState<string | null>(null);
  const [secretFor, setSecretFor] = useState<string | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['aliases'] });
  const remove = useMutation({ mutationFn: deleteAlias, onSuccess: refresh });

  /** 测试结果只留在本地 state，不进缓存。 */
  const runTest = async (name: string) => {
    setTesting(name);
    try {
      const result = await testAlias(name);
      setTested((current) => ({ ...current, [name]: result }));
    } catch (error) {
      setTested((current) => ({ ...current, [name]: { success: false, message: (error as Error).message } }));
    } finally {
      setTesting(null);
    }
  };

  const confirmRemove = (name: string) => {
    if (!window.confirm(`删除别名「${name}」？\n\n图谱数据与已存密码不会一起删除，需要另行清理。此操作不可撤销。`)) return;
    remove.mutate(name);
  };

  return (
    <div className="settings-block">
      <div className="settings-block-head">
        <h2>数据源</h2>
        <ActionIcon action="add" label="新建数据源" primary size="md" onClick={onAdd} />
      </div>

      {isLoading && <p className="settings-hint">加载中…</p>}
      {isError && <p className="settings-alert" role="alert">读取失败</p>}

      <table className="settings-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>图谱</th>
            <th>写入</th>
            <th title="打开后，对应操作会停在评审页等放行">审批</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.aliases.map((a) => (
            <tr key={a.name}>
              <td>
                <span className="settings-name">{a.name}</span>
                {a.description && <small>{a.description}</small>}
              </td>
              <td>{a.dbType || '—'}</td>
              <td>
                {a.graphAvailable ? (
                  <span className="settings-num">
                    {a.tables ?? 0} 表 / {a.relations ?? 0} 关系
                  </span>
                ) : (
                  <span className="settings-dim">未导入</span>
                )}
              </td>
              <td>{a.readOnly ? <span className="settings-dim">只读</span> : '可写'}</td>
              <td><ApprovalSwitches alias={a} /></td>
              <td>
                <div className="settings-row-actions">
                  <ActionIcon action="edit" label={`编辑 ${a.name}`} onClick={() => onEdit(a.name)} />
                  <ActionIcon
                    action="test"
                    label={testing === a.name ? `正在测试 ${a.name}` : `测试 ${a.name} 的连接`}
                    disabled={testing === a.name}
                    onClick={() => runTest(a.name)}
                  />
                  <Menu trigger={<ActionIcon action="more" label={`${a.name} 的更多操作`} />}>
                    <MenuItem onSelect={() => setSecretFor(a.name)}>设置密码</MenuItem>
                    <MenuItem danger disabled={remove.isPending} onSelect={() => confirmRemove(a.name)}>
                      删除数据源
                    </MenuItem>
                  </Menu>
                  {tested[a.name] && (
                    <p className={tested[a.name].success ? 'settings-test-ok' : 'settings-test-fail'} role="status">
                      {/* 成功时不拼后端 message（英文 "Connection successful"），失败时原因才有信息量。 */}
                      {tested[a.name].success
                        ? '连接成功'
                        : `连接失败${tested[a.name].message ? `：${tested[a.name].message}` : ''}`}
                    </p>
                  )}
                  {secretFor === a.name && (
                    <SecretField name={a.name} onDone={() => setSecretFor(null)} />
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {remove.isError && <p className="settings-alert" role="alert">删除失败：{remove.error.message}</p>}

      <div className="settings-gap">
        <h3>仅 CLI 可用</h3>
        <ul>
          <li><code>sql-cli &lt;alias&gt; secret status / delete</code></li>
        </ul>
      </div>
    </div>
  );
}

/**
 * 三个审批开关，直接在列表里勾。
 *
 * 不放进编辑表单：这是会被反复切换的运行时策略（上线前打开、验完关掉），
 * 埋在一个要滚动、要保存的长表单里会让人懒得改。
 */
function ApprovalSwitches({ alias }: { alias: AliasSummaryDto }) {
  const toggle = useMutation({
    mutationFn: (patch: UpdateAliasRequest) => updateAlias(alias.name, patch),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['aliases'] }),
  });

  const switches = [
    { key: 'approveQuery', label: '查', on: alias.approveQuery, hint: '查询要审批' },
    { key: 'approveUpdate', label: '改', on: alias.approveUpdate, hint: '增删改要审批' },
    // 开 = aliases.yaml 里的 graphApproval: manual，关 = auto（不是「不留底」，
    // 两种模式的审批记录一样全，区别只是要不要等人）
    { key: 'approveGraph', label: '图', on: alias.approveGraph,
      hint: '图谱变更要审批（graphApproval: manual）；关闭则自动批准并留底' },
  ] as const;

  return (
    <span className="settings-switches">
      {switches.map((item) => (
        <label key={item.key} title={`${item.hint}${item.on ? '（已开启）' : ''}`}>
          <input
            type="checkbox"
            checked={item.on}
            disabled={toggle.isPending}
            onChange={(event) => toggle.mutate({ [item.key]: event.target.checked })}
          />
          {item.label}
        </label>
      ))}
      {/* 带上原因：光说"保存失败"时，403 和别名写不进去看起来一模一样 */}
      {toggle.isError && <small role="alert">保存失败：{toggle.error.message}</small>}
    </span>
  );
}

/**
 * 行内设置密码。
 *
 * 值只活在这个组件的 state 里：不进 react-query 缓存、不进全局 store、提交后立即清空，
 * 响应体也不含它。
 */
function SecretField({ name, onDone }: { name: string; onDone: () => void }) {
  const [value, setValue] = useState('');
  const save = useMutation({
    mutationFn: () => setAliasSecret(name, value),
    onSuccess: () => { setValue(''); onDone(); },
  });

  return (
    <form
      className="settings-secret"
      onSubmit={(event) => { event.preventDefault(); if (value) save.mutate(); }}
    >
      <input
        type="password"
        autoComplete="new-password"
        aria-label={`${name} 的数据库密码`}
        placeholder="数据库密码"
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
      <Button type="submit" disabled={!value || save.isPending} variant="primary" size="sm">
        {save.isPending ? '写入中…' : '保存'}
      </Button>
      <Button onClick={() => { setValue(''); onDone(); }} size="sm">取消</Button>
      {save.isError && <small role="alert">写入失败：{save.error.message}</small>}
    </form>
  );
}
