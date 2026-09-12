/**
 * 规则讲解：14 类规则各自限制什么场景、达到什么效果、怎么配。
 *
 * 类别名一律英文原值展示 —— 它是 YAML 里的 category 取值，也是 CLI 输出里的
 * ruleId，翻译成中文会和实际配置对不上（CLAUDE.md 的枚举值约定）。
 */
import './rule-catalog.css';

/** DDL 执行 = 启用后 CREATE / ALTER / DROP 经过 sql-cli 执行时自动检查，不用 agent 先想起来跑评审。 */
type Command = 'DDL 执行' | 'design review' | 'migration lint' | 'policy check';

interface RuleDoc {
  category: string;
  scene: string;
  effect: string;
  commands: Command[];
  note?: string;
  example: string;
}

const STRUCTURE: RuleDoc[] = [
  {
    category: 'naming_convention',
    scene: '新建表、字段、索引时命名风格不统一，大小写和下划线混用。',
    effect: '名字必须匹配指定正则，不合规的命名在 DDL 落库前被挡下。',
    commands: ['design review', 'migration lint'],
    note: '只管「名字长得合不合规」。要求同一概念用同一个名字是 controlled_vocabulary 的事。'
      + '这一类默认 scope: change，policy check 不查存量图谱——遗留库上的表名不会为了规则去改，'
      + '实测一次全量跑出 1215 条命名违规、豁免 0 条，之后没人再跑。要查就写 scope: all，'
      + '或临时加 policy check --all。',
    example: `- id: naming_convention
  category: naming_convention
  severity: warning
  enforcement: advisory
  scope: change
  when: {}
  statement:
    tableNameRegex: "^[a-z][a-z0-9_]*$"
    columnNameRegex: "^[a-z][a-z0-9_]*$"
    indexNameRegex: "^(pk|uk|idx)_[a-z0-9_]+$"
  remediation: 使用小写 snake_case，索引按 pk_/uk_/idx_ 前缀命名`,
  },
  {
    category: 'required_business_columns',
    scene: '业务表缺审计字段；或各表都有 created_at，但有的 datetime 有的 varchar，有的可空有的不可空。字段名必须完全一致，create_time 不算 created_at。',
    effect: '不只检查字段在不在，还逐项比对类型、长度、精度、可空性、默认值、注释、onUpdate，让全库审计字段长得一模一样。',
    commands: ['DDL 执行', 'design review', 'migration lint', 'policy check'],
    note: '启用后 agent 通过 sql-cli 建表 / 改表时自动检查：required 缺字段直接拒绝并回修法，advisory 只提示。'
      + '也可以让 agent 从存量表归纳后自己提议：sql-cli <alias> schema policy rule add --category required_business_columns'
      + ' --column "created_at datetime notnull" --reason "…"，走别名的 graphApproval，'
      + 'manual 时进评审页待审批，批准才写入并启用。',
    example: `- id: required_business_columns
  category: required_business_columns
  severity: error
  enforcement: required
  when:
    tableTypeAny: [base_table]
  statement:
    columns:
      - name: created_at
        type: datetime
        nullable: false
        defaultValue: CURRENT_TIMESTAMP
        comment: 创建时间
      - name: updated_at
        type: datetime
        nullable: false
        defaultValue: CURRENT_TIMESTAMP
        onUpdate: CURRENT_TIMESTAMP
        comment: 更新时间
  remediation: 补齐创建时间和更新时间字段`,
  },
  {
    category: 'primary_key_required',
    scene: '业务表没有主键。',
    effect: '无主键的表直接判违规。主键同时是 UPDATE/DELETE 能否生成恢复 SQL 的前提 —— 没有主键就回滚不了。',
    commands: ['design review', 'migration lint', 'policy check'],
    example: `- id: primary_key_required
  category: primary_key_required
  severity: error
  enforcement: required
  when:
    tableTypeAny: [base_table]
  statement: {}
  remediation: 为业务表增加稳定主键`,
  },
  {
    category: 'money_type_decimal',
    scene: '金额、单价、费率用 float/double 存，累加后出现精度误差。',
    effect: '按 when 选中的列，数据类型必须落在白名单内。',
    commands: ['design review', 'migration lint', 'policy check'],
    note: '名字起窄了 —— 它其实是通用的「这类列必须用这些类型」，换掉 when 和 allowedTypes 就能管时间字段、编号字段。',
    example: `- id: money_type_decimal
  category: money_type_decimal
  severity: error
  enforcement: blocking
  when:
    columnNameRegex: "(?i)(amount|money|price|fee|rate)"
  statement:
    allowedTypes: [decimal, numeric]
  remediation: 将浮点字段改为 decimal/numeric`,
  },
  {
    category: 'controlled_vocabulary',
    scene: '同一个业务概念在库里有好几个名字：phone / tel / mobile / telephone 并存。',
    effect: '字段名命中同义词且不等于标准名时判违规。',
    commands: ['design review', 'migration lint', 'policy check'],
    note: '和 naming_convention 的区别：正则管形状，这条管语义。正则再严也拦不住把手机号叫 tel。',
    example: `- id: controlled_vocabulary
  category: controlled_vocabulary
  severity: error
  enforcement: required
  when: {}
  statement:
    concepts:
      - standard: phone
        aliases: [tel, mobile, telephone, phone_no]
      - standard: order_no
        aliases: [order_code, order_num, order_number]
      - standard: user_id
        aliases: [uid, member_id]
  remediation: 改用标准字段名`,
  },
  {
    category: 'business_unique_index',
    scene: '订单号、单据号这类业务唯一键只是普通字段，重复数据靠应用层兜。',
    effect: '指定的组合键必须有对应唯一索引；statement.columns 留空时改按 when 选列，逐列检查单列唯一索引。',
    commands: ['design review', 'migration lint', 'policy check'],
    example: `- id: business_unique_index
  category: business_unique_index
  severity: error
  enforcement: required
  when:
    columnNameRegex: "(?i)_(no|code)$"
  statement: {}
  remediation: 为业务编号字段建唯一索引`,
  },
  {
    category: 'status_field_dictionary',
    scene: 'status = 3 是什么意思，翻代码才知道。',
    effect: '状态字段必须有枚举取值、字典引用或文字说明，三者有其一即可。',
    commands: ['design review', 'migration lint', 'policy check'],
    example: `- id: status_field_dictionary
  category: status_field_dictionary
  severity: warning
  enforcement: advisory
  when:
    semanticTypeAny: [status]
  statement:
    dictionaryAttribute: dictionaryRef
    descriptionAttribute: dictionaryDescription
  remediation: 在字段上补充枚举值或字典说明`,
  },
  {
    category: 'sensitive_column_policy',
    scene: '手机号、身份证、银行卡入库，没人声明这些字段要不要加密、查询时要不要脱敏。',
    effect: '命中的字段必须标注 semanticType，并声明保护策略属性。',
    commands: ['design review', 'migration lint', 'policy check'],
    note: '保护策略属性目前没有写入入口，启用后无法修复 —— 建议先设为 advisory，等它和 SM4 解密列打通再提级。',
    example: `- id: sensitive_column_policy
  category: sensitive_column_policy
  severity: warning
  enforcement: advisory
  when:
    columnNameRegex: "(?i)(phone|mobile|email|id_card|bank_card|password)"
  statement:
    policyAttribute: sensitivePolicy
  remediation: 标注 semanticType 并声明保护策略`,
  },
  {
    category: 'fact_table_grain_required',
    scene: '事实表建完没人声明「一行代表什么」，指标展开时才发现口径含糊，只能靠猜。',
    effect: '打了 fact 标签的表，grain 为空即判违规 —— 把粒度声明从「靠自觉」变成「过不了评审」。',
    commands: ['design review', 'migration lint', 'policy check'],
    note: 'grain 是 expand-metric 扇形陷阱检查的输入：没有它，join 放大行数时程序判断不出来。',
    example: `- id: fact_table_grain_required
  category: fact_table_grain_required
  severity: warning
  enforcement: advisory
  when:
    tableTagsAny: [fact]
  statement: {}
  remediation: 用 sql-cli <alias> schema edit --grain '一行代表…' 声明表粒度`,
  },
];

const RELATION: RuleDoc[] = [
  {
    category: 'relation_column_index',
    scene: 'JOIN 用的关联字段没有索引，关联查询全表扫。',
    effect: '图谱里每条关系的起点列必须有左前缀索引。',
    commands: ['design review', 'migration lint', 'policy check'],
    note: '未经验证的 join_observed 关系自动降级为提示，不因为一条推断出来的关系就阻断。',
    example: `- id: relation_column_index
  category: relation_column_index
  severity: warning
  enforcement: required
  when: {}
  statement: {}
  remediation: 为关联字段补索引`,
  },
  {
    category: 'join_column_type_match',
    scene: '两张表 JOIN 的字段一个 bigint 一个 varchar，隐式转换让索引失效。',
    effect: '类型不同且不在同一兼容组判为 incompatible；类型相同但长度、精度、小数位不同判为 suspicious。',
    commands: ['design review', 'migration lint', 'policy check'],
    example: `- id: join_column_type_match
  category: join_column_type_match
  severity: error
  enforcement: required
  when: {}
  statement:
    compatibleTypeGroups:
      - [int, bigint, smallint]
      - [char, varchar]
  remediation: 统一关联两端的字段类型`,
  },
];

const SQL: RuleDoc[] = [
  {
    category: 'dangerous_dml_guard',
    scene: '迁移脚本里的 UPDATE/DELETE 漏写 WHERE，或目标表没主键导致改错了回不去。',
    effect: '检查三件事：WHERE 是否存在、目标表在图谱里是否有主键、是否配了验证 SQL。',
    commands: ['migration lint'],
    note: '需要 SQL 输入。放进 design review 用的规则集会让整次评估报错 —— SQL 类规则单独建一个规则集。',
    example: `- id: dangerous_dml_guard
  category: dangerous_dml_guard
  severity: error
  enforcement: blocking
  when: {}
  statement: {}
  remediation: 补 WHERE 条件与验证 SQL`,
  },
  {
    category: 'migration_safety_check',
    scene: '迁移脚本 DROP 字段、改类型、加索引、回填数据，上线才发现锁表或不可回滚。',
    effect: '识别 DROP / RENAME / ADD_INDEX / ADD_CONSTRAINT / ALTER_TYPE / SET_NOT_NULL / BACKFILL 七类风险；破坏性变更要求 --rollback，命中风险要求 --precheck 与 --postcheck，数据回填还要求分批、断点、幂等三项策略。',
    commands: ['migration lint'],
    note: '需要变更集输入，同样不要和结构规则混在一个规则集里。',
    example: `- id: migration_safety_check
  category: migration_safety_check
  severity: error
  enforcement: required
  when: {}
  statement: {}
  remediation: 补充回滚方案与前后校验 SQL`,
  },
  {
    category: 'dialect_sql_pattern',
    scene: '写出 Oracle 风格的 FETCH FIRST 丢给 MySQL，或用了当前版本不支持的函数。',
    effect: 'SQL 命中正则就给出该数据库的正确写法提示。用于提示而非阻断。',
    commands: ['migration lint'],
    note: '随程序发布的 rules/dialect/*.yaml 用的就是这一类；也可以当通用 SQL 检查用，比如拦 SELECT *。',
    example: `- id: pagination.limit
  category: dialect_sql_pattern
  severity: info
  enforcement: advisory
  when: {}
  statement:
    matchRegex: "(?i)FETCH\\\\s+FIRST"
    topic: pagination
    message: MySQL 用 LIMIT size OFFSET offset，不支持 FETCH FIRST
  remediation: 改用 LIMIT 语法`,
  },
];

const GROUPS: { title: string; hint: string; rules: RuleDoc[] }[] = [
  { title: '结构规则', hint: '只读图谱事实，设计新表时最常用', rules: STRUCTURE },
  { title: '关系规则', hint: '读图谱里的关系边，图谱补得越全越准', rules: RELATION },
  { title: 'SQL 与迁移规则', hint: '需要 SQL 或变更集输入，只在 migration lint 生效', rules: SQL },
];

function RuleCard({ rule }: { rule: RuleDoc }) {
  return <article className="catalog-rule">
    <header>
      <code>{rule.category}</code>
      {rule.commands.map((command) => <span className="catalog-cmd" key={command}>{command}</span>)}
    </header>
    <dl>
      <dt>限制场景</dt><dd>{rule.scene}</dd>
      <dt>达到效果</dt><dd>{rule.effect}</dd>
    </dl>
    {rule.note && <p className="catalog-note">{rule.note}</p>}
    <pre>{rule.example}</pre>
  </article>;
}

export function RuleCatalog() {
  return <section className="catalog">
    <h2>规则讲解</h2>
    <p className="catalog-intro">
      规则写在规则集的 <code>rules[]</code> 里。<code>when</code> 决定这条规则管哪些表和字段，
      <code>statement</code> 是该类别专属的检查配置 —— 两者的可用字段随 <code>category</code> 变化，
      照下面对应类别的示例填。
    </p>
    <p className="catalog-intro">
      <code>severity</code>（info / warning / error / critical）只影响展示；决定检查是否失败的是
      <code>enforcement</code>：<code>advisory</code> 仅提示，<code>required</code> 与 <code>blocking</code> 都会让检查不通过。
    </p>
    <p className="catalog-intro">
      <code>scope</code> 决定规则跑在哪里：<code>change</code> 只查 design review / migration lint
      送进来的新 DDL 碰到的表，<code>all</code> 还会查存量图谱。除 <code>naming_convention</code>
      默认 <code>change</code> 外都默认 <code>all</code>；<code>policy check --all</code> 可临时全查一次。
    </p>
    {GROUPS.map((group) => <div className="catalog-group" key={group.title}>
      <h3>{group.title}<small>{group.hint}</small></h3>
      {group.rules.map((rule) => <RuleCard rule={rule} key={rule.category} />)}
    </div>)}
  </section>;
}
