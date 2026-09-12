import { useState } from 'react';
import { ActionIcon } from '../../ui/ActionIcon';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  createDriver,
  deleteDriver,
  getDrivers,
  setDefaultDriver,
  updateDriver,
  uploadDriverJar,
  DEFAULT_DRIVER_CLASS,
  type DriverDto,
} from '../../api/drivers';
import { queryClient } from '../../api/queryClient';
import '../alias-home/alias-home.css';
import { Button, buttonClass } from '../../ui/Button';
import { StatusDot } from '../../ui/StatusDot';
import { Select } from '../../ui/Select';

const DB_TYPES = ['mysql', 'postgresql', 'oracle', 'clickhouse'];

/**
 * 驱动管理。
 *
 * 列表里最有价值的是 jar 状态灯：jar 路径写错时，用户原本看到的是「连接失败」，
 * 离根因隔了十万八千里。这里在列表加载时就把它暴露成「jar 文件不存在」。
 */
export function DriverSettings() {
  const [editing, setEditing] = useState<DriverDto | null | 'new'>(null);

  const drivers = useQuery({
    queryKey: ['drivers'],
    queryFn: ({ signal }) => getDrivers(signal),
    staleTime: 30_000,
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['drivers'] });
  const remove = useMutation({ mutationFn: deleteDriver, onSuccess: refresh });
  const makeDefault = useMutation({ mutationFn: setDefaultDriver, onSuccess: refresh });

  if (editing) {
    return (
      <DriverForm
        driver={editing === 'new' ? null : editing}
        onDone={() => { setEditing(null); refresh(); }}
      />
    );
  }

  return (
    <div className="settings-block">
      <div className="settings-block-head">
        <h2>驱动</h2>
        <ActionIcon action="add" label="新建驱动" primary size="md" onClick={() => setEditing('new')} />
      </div>

      {drivers.isLoading && <p className="settings-hint">加载中…</p>}
      {drivers.isError && <p className="settings-alert" role="alert">读取失败</p>}
      {remove.isError && <p className="settings-alert" role="alert">{remove.error.message}</p>}
      {makeDefault.isError && <p className="settings-alert" role="alert">{makeDefault.error.message}</p>}

      <table className="settings-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>驱动类</th>
            <th>jar</th>
            <th>引用</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {drivers.data?.map((driver) => (
            <tr key={driver.name}>
              <td>
                <span className="settings-name">{driver.name}</span>
                {driver.isDefault && <small>该类型的默认驱动</small>}
              </td>
              <td>{driver.dbType || '—'}</td>
              <td className="settings-mono">{driver.driverClass || '—'}</td>
              <td>
                <StatusDot tone={driver.status} label={driver.statusDetail} />
              </td>
              <td>
                {driver.usedBy.length === 0 ? (
                  <span className="settings-dim">未被使用</span>
                ) : (
                  <span title={driver.usedBy.join('、')}>{driver.usedBy.length} 个数据源</span>
                )}
              </td>
              <td>
                <div className="settings-row-actions">
                  <ActionIcon action="edit" label={`编辑 ${driver.name}`} onClick={() => setEditing(driver)} />
                  {!driver.isDefault && driver.dbType && (
                    <Button
                      disabled={makeDefault.isPending}
                      onClick={() => makeDefault.mutate(driver.name)}
                      size="sm"
                    >
                      设为默认
                    </Button>
                  )}
                  <ActionIcon
                    action="delete"
                    label={
                      driver.usedBy.length > 0
                        ? `有 ${driver.usedBy.length} 个数据源正在使用：${driver.usedBy.join('、')}`
                        : `删除驱动 ${driver.name}`
                    }
                    disabled={remove.isPending || driver.usedBy.length > 0}
                    onClick={() => {
                      if (window.confirm(`删除驱动「${driver.name}」？`)) remove.mutate(driver.name);
                    }}
                  />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <p className="settings-hint">
        jar 状态：绿=可加载，黄=文件在但驱动类加载失败，红=文件不存在。鼠标停留看具体原因。
      </p>
    </div>
  );
}

function DriverForm({ driver, onDone }: { driver: DriverDto | null; onDone: () => void }) {
  const isEdit = driver !== null;
  const [name, setName] = useState(driver?.name ?? '');
  const [dbType, setDbType] = useState(driver?.dbType ?? 'mysql');
  const [driverClass, setDriverClass] = useState(driver?.driverClass ?? DEFAULT_DRIVER_CLASS.mysql);
  const [jars, setJars] = useState<string[]>(driver?.jars ?? []);
  const [manualJar, setManualJar] = useState('');

  const upload = useMutation({
    mutationFn: (file: File) => uploadDriverJar(dbType, file),
    onSuccess: (result) => {
      setJars((current) => (current.includes(result.path) ? current : [...current, result.path]));
    },
  });

  const save = useMutation({
    mutationFn: () =>
      isEdit
        ? updateDriver(driver!.name, { dbType, driverClass, jars })
        : createDriver({ name, dbType, driverClass, jars }),
    onSuccess: onDone,
  });

  const addManualJar = () => {
    const value = manualJar.trim();
    if (!value) return;
    setJars((current) => (current.includes(value) ? current : [...current, value]));
    setManualJar('');
  };

  return (
    <form
      className="driver-form-page"
      onSubmit={(event) => { event.preventDefault(); save.mutate(); }}
    >
      <header className="alias-form-header">
        <Button
          aria-label="返回驱动列表"
          title="返回驱动列表"
          onClick={onDone}
          size="sm"
          icon
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
          </svg>
        </Button>
        <h1>{isEdit ? driver!.name : '新建驱动'}</h1>
        {isEdit && <span className="alias-form-tag">编辑</span>}
      </header>

      {save.isError && <p className="alias-form-error" role="alert">保存失败：{save.error.message}</p>}
      {upload.isError && <p className="alias-form-error" role="alert">上传失败：{upload.error.message}</p>}

      <section className="alias-section">
        <h2>基本</h2>
        <div className="alias-grid-2">
          <label>
            名称
            <input
              required
              value={name}
              disabled={isEdit}
              onChange={(event) => setName(event.target.value)}
              placeholder="mysql8"
            />
          </label>
          <label>
            类型
            <Select
              value={dbType}
              onChange={(event) => {
                const next = event.target.value;
                setDbType(next);
                // 类名没被手工改过时跟着类型走，省掉去查驱动类全名
                if (!driverClass || Object.values(DEFAULT_DRIVER_CLASS).includes(driverClass)) {
                  setDriverClass(DEFAULT_DRIVER_CLASS[next] ?? '');
                }
              }}
            >
              {DB_TYPES.map((type) => (
                <option key={type} value={type}>{type}</option>
              ))}
            </Select>
          </label>
        </div>
        <label>
          驱动类
          <input
            required
            className="alias-mono"
            value={driverClass}
            onChange={(event) => setDriverClass(event.target.value)}
          />
        </label>
      </section>

      <section className="alias-section">
        <h2>jar 文件</h2>

        {jars.length > 0 && (
          <ul className="driver-jar-list">
            {jars.map((jar) => (
              <li key={jar}>
                <code>{jar}</code>
                <ActionIcon
                  action="delete"
                  label={`从这个驱动移除 ${jar}（不删除文件）`}
                  onClick={() => setJars((current) => current.filter((item) => item !== jar))}
                />
              </li>
            ))}
          </ul>
        )}

        <div className="driver-jar-add">
          <label className={buttonClass()}>
            {upload.isPending ? '上传中…' : '上传 jar'}
            <input
              type="file"
              accept=".jar"
              hidden
              disabled={upload.isPending}
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) upload.mutate(file);
                event.target.value = '';
              }}
            />
          </label>
          <span className="driver-jar-or">或填写已有路径</span>
          <input
            className="alias-mono"
            value={manualJar}
            onChange={(event) => setManualJar(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') { event.preventDefault(); addManualJar(); }
            }}
            placeholder="./drivers/mysql/mysql-connector-j-8.3.0.jar"
          />
          <ActionIcon action="add" label="添加 jar 路径" onClick={addManualJar} />
        </div>

        {jars.length === 0 && (
          <p className="driver-jar-empty">
            未添加 jar 时会尝试从 classpath 加载驱动，多数情况下会失败。
          </p>
        )}
      </section>

      <div className="alias-form-actions">
        <Button onClick={onDone}>取消</Button>
        <Button type="submit" disabled={save.isPending || upload.isPending} variant="primary">
          {save.isPending ? '保存中…' : '保存'}
        </Button>
      </div>
    </form>
  );
}
