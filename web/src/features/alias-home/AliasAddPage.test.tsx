import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { AliasAddPage } from './AliasAddPage';

vi.mock('../../api/aliases', () => ({
  createAlias: vi.fn(),
  updateAlias: vi.fn(),
  getAlias: vi.fn(),
  checkSqlitePath: vi.fn(),
  listServerDirectory: vi.fn(),
}));
vi.mock('../../api/workspace', () => ({ getSchemaCatalog: vi.fn() }));
vi.mock('../../api/drivers', () => ({ getDrivers: vi.fn() }));

const { createAlias, checkSqlitePath, listServerDirectory } = await import('../../api/aliases');
const { getDrivers } = await import('../../api/drivers');

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AliasAddPage onDone={vi.fn()} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  // 清掉调用记录——否则上一个用例里的 createAlias 调用会被这个用例的
  // `not.toHaveBeenCalled()` 断言当成「刚刚发生」。
  vi.clearAllMocks();
  vi.mocked(getDrivers).mockResolvedValue([]);
  vi.mocked(createAlias).mockResolvedValue({ name: 'shop-local', status: 'created' });
});

test('选 sqlite 后连接字段切换成文件路径，账号密码整段消失', async () => {
  renderPage();

  expect(screen.getByLabelText('JDBC URL')).toBeTruthy();
  expect(screen.getByLabelText('用户名')).toBeTruthy();

  await userEvent.selectOptions(screen.getByLabelText('类型'), 'sqlite');

  expect(screen.queryByLabelText('JDBC URL')).toBeNull();
  expect(screen.queryByLabelText('用户名')).toBeNull();
  expect(screen.queryByLabelText('密码引用')).toBeNull();
  expect(screen.getByLabelText('数据库文件路径')).toBeTruthy();
});

test('数据库文件路径留空时无法提交（原生 required 拦截）', async () => {
  renderPage();
  await userEvent.selectOptions(screen.getByLabelText('类型'), 'sqlite');

  const pathInput = screen.getByLabelText('数据库文件路径');
  expect(pathInput).toBeRequired();
  expect(pathInput).toHaveValue('');
});

test('保存前先测一次路径，文件已存在时直接保存并显示结果', async () => {
  vi.mocked(checkSqlitePath).mockResolvedValue({
    exists: true,
    path: 'D:/data/shop.db',
    message: '文件已存在',
  });
  renderPage();

  await userEvent.selectOptions(screen.getByLabelText('类型'), 'sqlite');
  await userEvent.type(screen.getByLabelText('名称'), 'shop-local');
  await userEvent.type(screen.getByLabelText('数据库文件路径'), 'D:/data/shop.db');
  await userEvent.click(screen.getByRole('button', { name: '保存' }));

  expect(await screen.findByText('文件已存在')).toBeTruthy();
  await waitFor(() => expect(createAlias).toHaveBeenCalledTimes(1));
  const payload = vi.mocked(createAlias).mock.calls[0][0];
  // sqlite 没有账号密码，不该被塞进一个默认的 keyring:<name>
  expect(payload.secretRef).toBe('');
  // 连接目标走 database 字段（策略层从这里读路径），不是拼一个完整 jdbcUrl
  expect(payload.database).toBe('D:/data/shop.db');
  expect(payload.sqliteCreateIfMissing).toBeFalsy();
});

test('文件不存在时先警告，用户确认后才继续保存', async () => {
  vi.mocked(checkSqlitePath).mockResolvedValue({
    exists: false,
    path: 'D:/data/missing.db',
    message: '此路径下没有库文件，保存后 SQLite 会在这里新建一个空库',
  });
  const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
  renderPage();

  await userEvent.selectOptions(screen.getByLabelText('类型'), 'sqlite');
  await userEvent.type(screen.getByLabelText('名称'), 'shop-local');
  await userEvent.type(screen.getByLabelText('数据库文件路径'), 'D:/data/missing.db');
  await userEvent.click(screen.getByRole('button', { name: '保存' }));

  expect(await screen.findByText(/此路径下没有库文件/)).toBeTruthy();
  expect(confirmSpy).toHaveBeenCalled();
  // 用户拒绝了确认弹窗——不能保存一个大概率拼错的路径
  expect(createAlias).not.toHaveBeenCalled();

  confirmSpy.mockReturnValue(true);
  await userEvent.click(screen.getByRole('button', { name: '保存' }));
  await waitFor(() => expect(createAlias).toHaveBeenCalledTimes(1));
  // 用户确认新建后，这个开关必须真的带上——不然向导退出后第一次真正连接
  // 还是会被 sqlite 策略的「文件不存在」检查拒绝，等于白确认了一次
  expect(vi.mocked(createAlias).mock.calls[0][0].sqliteCreateIfMissing).toBe(true);
});

test('点击浏览打开服务端目录选择器，点目录进入下一级，点文件回填路径并关闭选择器', async () => {
  vi.mocked(listServerDirectory).mockImplementation(async (path) => {
    if (!path) {
      return { path: null, parent: null, entries: [{ name: 'data', path: 'D:/data', dir: true }] };
    }
    return {
      path: 'D:/data',
      parent: 'D:/',
      entries: [{ name: 'shop.db', path: 'D:/data/shop.db', dir: false }],
    };
  });
  renderPage();

  await userEvent.selectOptions(screen.getByLabelText('类型'), 'sqlite');
  await userEvent.click(screen.getByRole('button', { name: '浏览…' }));

  expect(await screen.findByText('data/')).toBeTruthy();
  await userEvent.click(screen.getByText('data/'));

  const fileEntry = await screen.findByText('shop.db');
  await userEvent.click(fileEntry);

  expect(screen.getByLabelText('数据库文件路径')).toHaveValue('D:/data/shop.db');
  // 选中之后选择器自己收起，不需要用户再点一次关闭
  expect(screen.queryByRole('group', { name: '选择数据库文件' })).toBeNull();
});

test('取消选择器不改动已经填好的路径', async () => {
  vi.mocked(listServerDirectory).mockResolvedValue({ path: null, parent: null, entries: [] });
  renderPage();

  await userEvent.selectOptions(screen.getByLabelText('类型'), 'sqlite');
  await userEvent.type(screen.getByLabelText('数据库文件路径'), 'D:/data/shop.db');
  await userEvent.click(screen.getByRole('button', { name: '浏览…' }));
  await screen.findByRole('group', { name: '选择数据库文件' });

  await userEvent.click(screen.getByRole('button', { name: '关闭选择器' }));

  expect(screen.queryByRole('group', { name: '选择数据库文件' })).toBeNull();
  expect(screen.getByLabelText('数据库文件路径')).toHaveValue('D:/data/shop.db');
});
