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

export function SettingsPage() {
  const [tab, setTab] = useState<Tab>('sources');
  const [editing, setEditing] = useState<string | null>(null);

  return (
    <div className="page settings settings-redesign">
      <header className="settings-head">
        <div className="settings-title">
          <span className="settings-eyebrow">Workspace settings</span>
          <h1>设置</h1>
          <p>管理数据源、驱动和执行保护策略。</p>
        </div>
        <div className="settings-tabs" role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'sources'} className={tab === 'sources' ? 'is-active' : ''} onClick={() => setTab('sources')}>数据源</button>
          <button type="button" role="tab" aria-selected={tab === 'drivers'} className={tab === 'drivers' ? 'is-active' : ''} onClick={() => setTab('drivers')}>驱动</button>
        </div>
      </header>

      {tab === 'sources' && (editing !== null ? (
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

  const aliases = data?.aliases ?? [];
  const graphReady = aliases.filter((item) => item.graphAvailable).length;
  const writable = aliases.filter((item) => !item.readOnly).length;
  const guarded = aliases.filter((item) => item.approveQuery || item.approveUpdate || item.approveGraph).length;

  return (
    <div className="settings-block">
      <div className="settings-block-head settings-block-head-v3">
        <div>
          <h2>数据源控制台</h2>
          <p>连接、图谱状态和审批策略集中管理；高频策略可以直接在列表中切换。</p>
        </div>
        <ActionIcon action="add" label="新建数据源" primary size="md" onClick={onAdd} />
      </div>

      {!isLoading && !isError && (
        <div className="settings-overview" aria-label="数据源概览">
          <div className="settings-overview-card"><b>{aliases.length}</b><span>数据源</span></div>
          <div className="settings-overview-card"><b>{graphReady}</b><span>图谱已就绪</span></div>
          <div className="settings-overview-card"><b>{writable}</b><span>可写</span></div>
          <div className="settings-overview-card"><b>{guarded}</b><span>启用审批保护</span></div>
        </div>
      )}

      {isLoading && <div className="settings-skeleton" aria-label="加载数据源"><span /><span /><span /></div>}
      {isError && <p className="settings-alert" role="alert">读取失败，请确认服务仍在运行。</p>}

      {!isLoading && !isError && aliases.length === 0 && (
        <div className="product-empty">
          <strong>还没有数据源</strong>
          <p>添加第一个数据库连接后，就可以在工作台执行 SQL，并逐步导入 Schema Graph。</p>
          <Button variant="primary" onClick={onAdd}>新建数据源</Button>
        </div>
      )}

      {aliases.length > 0 && (
        <div className="settings-table-wrap">
          <table className="settings-table">
            <thead>
              <tr>
                <th>数据源</th><th>类型</th><th>图谱</th><th>访问模式</th><th title="开启后，对应操作会进入评审队列">审批策略</th><th><span className="sr-only">操作</span></th>
              </tr>
            </thead>
            <tbody>
              {aliases.map((a) => (
                <tr key={a.name}>
                  <td><span className="settings-name">{a.name}</span>{a.description && <small>{a.description}</small>}</td>
                  <td><span className="settings-type-badge">{a.dbType || 'unknown'}</span></td>
                  <td>{a.graphAvailable ? <span className="settings-state is-ok"><i />{a.tables ?? 0} 表 · {a.relations ?? 0} 关系</span> : <span className="settings-state is-muted"><i />未导入</span>}</td>
                  <td><span className={`settings-state ${a.readOnly ? 'is-muted' : 'is-ok'}`}><i />{a.readOnly ? '只读' : '可写'}</span></td>
                  <td><ApprovalSwitches alias={a} /></td>
                  <td>
                    <div className="settings-row-actions">
                      <ActionIcon action="edit" label={`编辑 ${a.name}`} onClick={() => onEdit(a.name)} />
                      <ActionIcon action="test" label={testing === a.name ? `正在测试 ${a.name}` : `测试 ${a.name} 的连接`} disabled={testing === a.name} onClick={() => runTest(a.name)} />
                      <Menu trigger={<ActionIcon action="more" label={`${a.name} 的更多操作`} />}>
                        <MenuItem onSelect={() => setSecretFor(a.name)}>设置密码</MenuItem>
                        <MenuItem danger disabled={remove.isPending} onSelect={() => confirmRemove(a.name)}>删除数据源</MenuItem>
                      </Menu>
                      {tested[a.name] && <p className={tested[a.name].success ? 'settings-test-ok' : 'settings-test-fail'} role="status">{tested[a.name].success ? '连接成功' : `连接失败${tested[a.name].message ? `：${tested[a.name].message}` : ''}`}</p>}
                      {secretFor === a.name && <SecretField name={a.name} onDone={() => setSecretFor(null)} />}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {remove.isError && <p className="settings-alert" role="alert">删除失败：{remove.error.message}</p>}
      <div className="settings-gap"><h3>CLI 专用操作</h3><p>少数低频密钥维护仍保留在命令行：</p><code>sql-cli &lt;alias&gt; secret status / delete</code></div>
    </div>
  );
}

function ApprovalSwitches({ alias }: { alias: AliasSummaryDto }) {
  const toggle = useMutation({
    mutationFn: (patch: UpdateAliasRequest) => updateAlias(alias.name, patch),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['aliases'] }),
  });
  const switches = [
    { key: 'approveQuery', label: '查询', on: alias.approveQuery, hint: '查询执行前需要审批' },
    { key: 'approveUpdate', label: '写入', on: alias.approveUpdate, hint: '增删改执行前需要审批' },
    { key: 'approveGraph', label: '图谱', on: alias.approveGraph, hint: '图谱变更需要人工审批；关闭时自动批准并留底' },
  ] as const;
  return (
    <span className="settings-switches">
      {switches.map((item) => <label key={item.key} title={`${item.hint}${item.on ? '（已开启）' : ''}`}><input type="checkbox" checked={item.on} disabled={toggle.isPending} onChange={(event) => toggle.mutate({ [item.key]: event.target.checked })} />{item.label}</label>)}
      {toggle.isError && <small role="alert">保存失败：{toggle.error.message}</small>}
    </span>
  );
}

function SecretField({ name, onDone }: { name: string; onDone: () => void }) {
  const [value, setValue] = useState('');
  const save = useMutation({ mutationFn: () => setAliasSecret(name, value), onSuccess: () => { setValue(''); onDone(); } });
  return (
    <form className="settings-secret" onSubmit={(event) => { event.preventDefault(); if (value) save.mutate(); }}>
      <input type="password" autoComplete="new-password" aria-label={`${name} 的数据库密码`} placeholder="数据库密码" value={value} onChange={(event) => setValue(event.target.value)} />
      <Button type="submit" disabled={!value || save.isPending} variant="primary" size="sm">{save.isPending ? '写入中…' : '保存'}</Button>
      <Button onClick={() => { setValue(''); onDone(); }} size="sm">取消</Button>
      {save.isError && <small role="alert">写入失败：{save.error.message}</small>}
    </form>
  );
}
