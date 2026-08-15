/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Reviewed mainland-China rules that complement the checksum-first baseline. */
final class ChineseMainlandRules {
    private static final int FLAGS = Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE;
    private static final String FIELD_SEPARATOR = "[：:=#\\s]{0,8}";
    private static final String IDENTIFIER = "[A-Z0-9][A-Z0-9./_\\- ]{2,49}[A-Z0-9]";
    private static final String ACCOUNT = "[A-Z0-9](?:[ A-Z0-9-]{4,38})[A-Z0-9]";
    private static final String HAN_VALUE = "[^，。；;\\r\\n]{2,100}";
    private static final String PERSON = "[\\p{IsHan}·]{2,8}";

    private ChineseMainlandRules() {
    }

    static List<RuleDefinition> create() {
        List<RuleDefinition> rules = new ArrayList<>();

        // Identity, professional, medical and education identifiers.
        rules.add(context("CN_RESIDENT_ID_15_CONTEXT", "旧版15位居民身份证号", "identity", 95,
                "(?:旧版)?(?:居民)?身份证(?:号|号码)?", "[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}",
                Validators::chineseResidentId15));
        rules.add(context("CN_HOUSEHOLD_REGISTER_CONTEXT", "户口簿或户籍编号", "identity", 88,
                "户口簿(?:号|编号)?|户籍(?:号|编号)|户口(?:号|编号)", IDENTIFIER));
        rules.add(context("CN_BIRTH_CERTIFICATE_CONTEXT", "出生医学证明编号", "identity", 88,
                "出生医学证明(?:编号|号)?|出生证(?:编号|号)?", IDENTIFIER));
        rules.add(context("CN_SOCIAL_SECURITY_CARD_CONTEXT", "社会保障卡号", "identity", 90,
                "社会保障卡(?:号|号码)?|社保卡(?:号|号码)?", "[A-Z0-9][A-Z0-9 -]{7,29}[A-Z0-9]"));
        rules.add(context("CN_MEDICAL_INSURANCE_NUMBER_CONTEXT", "医疗保险或医保编号", "health_medical", 89,
                "医疗保险(?:号|编号)|医保(?:号|编号)|参保人编号", IDENTIFIER));
        rules.add(context("CN_DRIVER_LICENSE_CONTEXT", "中国驾驶证档案或证号", "identity", 90,
                "驾驶证(?:号|号码|档案编号)|机动车驾驶证(?:号|号码)?", IDENTIFIER));
        rules.add(context("CN_VEHICLE_LICENSE_CONTEXT", "机动车行驶证编号", "vehicle_property", 88,
                "行驶证(?:号|号码|编号)|机动车行驶证(?:号|号码|编号)?", IDENTIFIER));
        rules.add(context("CN_PASSPORT_CONTEXT", "中国护照号码字段", "identity", 91,
                "中国护照(?:号|号码)?|护照(?:号|号码)", "[A-Z][A-Z0-9]{7,16}"));
        rules.add(context("CN_MILITARY_ID_CONTEXT", "军人证件号码", "identity", 87,
                "军官证|士兵证|文职人员证|军人保障卡|军人证件", IDENTIFIER));
        rules.add(context("CN_POLICE_ID_CONTEXT", "人民警察证编号", "identity", 87,
                "人民警察证|警官证|警员证", IDENTIFIER));
        rules.add(context("CN_LAWYER_LICENSE_CONTEXT", "律师执业证号", "legal_case", 91,
                "律师执业证(?:号|号码)?|律师证(?:号|号码)?", "\\d{17}"));
        rules.add(context("CN_LEGAL_PROFESSIONAL_CERT_CONTEXT", "法律职业资格证书编号", "legal_case", 88,
                "法律职业资格证书(?:号|编号)?|法律职业资格证(?:号|编号)?", IDENTIFIER));
        rules.add(context("CN_NOTARY_LICENSE_CONTEXT", "公证员执业证号", "legal_case", 87,
                "公证员执业证(?:号|编号)?|公证员证(?:号|编号)?", IDENTIFIER));
        rules.add(context("CN_JUDGE_ID_CONTEXT", "法官工作或身份编号", "legal_case", 85,
                "法官(?:工作证|编号|工号)|审判员(?:编号|工号)", IDENTIFIER));
        rules.add(context("CN_PROCURATOR_ID_CONTEXT", "检察官工作或身份编号", "legal_case", 85,
                "检察官(?:工作证|编号|工号)|检察员(?:编号|工号)", IDENTIFIER));
        rules.add(context("CN_EMPLOYEE_NUMBER_CONTEXT", "员工编号或工号", "employment", 80,
                "员工编号|员工号|人员编号|工号|职工编号|雇员编号", IDENTIFIER));
        rules.add(context("CN_STUDENT_NUMBER_CONTEXT", "学号", "education", 84,
                "学号|学生编号|学生证号", "[A-Z0-9][A-Z0-9-]{4,29}"));
        rules.add(context("CN_EXAM_ADMISSION_NUMBER_CONTEXT", "准考证号", "education", 84,
                "准考证号|考试报名号|考生号", "[A-Z0-9][A-Z0-9-]{5,39}"));
        rules.add(context("CN_PATIENT_ID_CONTEXT", "患者编号", "health_medical", 86,
                "患者编号|患者ID|病人编号|病人ID|就诊卡号", IDENTIFIER));
        rules.add(context("CN_MEDICAL_RECORD_CONTEXT", "病历号、门诊号或住院号", "health_medical", 90,
                "病历号|病案号|门诊号|住院号|住院流水号", IDENTIFIER));
        rules.add(context("CN_PRESCRIPTION_NUMBER_CONTEXT", "处方编号", "health_medical", 84,
                "处方号|处方编号|电子处方号", IDENTIFIER));
        rules.add(context("CN_LAB_SAMPLE_CONTEXT", "检验或生物样本编号", "health_medical", 86,
                "检验单号|检查单号|样本编号|标本编号|病理号|切片号", IDENTIFIER));
        rules.add(context("CN_HEALTH_RECORD_CONTEXT", "居民健康档案编号", "health_medical", 87,
                "健康档案号|健康档案编号|居民健康档案编号", IDENTIFIER));
        rules.add(context("CN_BIOMETRIC_SAMPLE_CONTEXT", "生物特征样本编号", "identity", 88,
                "指纹编号|声纹编号|虹膜编号|DNA样本编号|DNA编号", IDENTIFIER));

        // Mainland banking, payment, tax, securities and insurance fields.
        rules.add(context("CN_BANK_ACCOUNT_CONTEXT", "中国银行账户字段", "financial", 92,
                "银行账号|银行账户|结算账户|收款账号|付款账号|基本账户|一般账户", ACCOUNT));
        rules.add(context("CN_CNAPS_CODE_CONTEXT", "中国现代化支付系统行号CNAPS", "financial", 90,
                "联行号|支付系统行号|CNAPS(?: code)?|银行行号", "\\d{12}"));
        rules.add(context("CN_BANK_BRANCH_CONTEXT", "开户银行及支行名称", "financial", 78,
                "开户行|开户银行|收款行|付款行|开户支行", "[^，。；;\\r\\n]{3,80}"));
        rules.add(context("CN_BANK_CARD_CVV_CONTEXT", "银行卡安全码CVV或CVC", "financial", 94,
                "CVV2?|CVC2?|安全码", "\\d{3,4}"));
        rules.add(context("CN_BANK_CARD_EXPIRY_CONTEXT", "银行卡有效期", "financial", 82,
                "银行卡有效期|卡片有效期|有效期至|expiry", "(?:0[1-9]|1[0-2])[/\\-](?:\\d{2}|20\\d{2})"));
        rules.add(context("CN_ALIPAY_ACCOUNT_CONTEXT", "支付宝账户", "financial", 89,
                "支付宝账号|支付宝账户|支付宝绑定手机|Alipay account", "[^，。；;\\r\\n]{5,80}"));
        rules.add(context("CN_WECHAT_PAY_ACCOUNT_CONTEXT", "微信支付账户", "financial", 88,
                "微信支付账号|微信支付账户|微信支付商户账号", "[^，。；;\\r\\n]{5,80}"));
        rules.add(context("CN_UNIONPAY_MERCHANT_CONTEXT", "银联商户号", "financial", 86,
                "银联商户号|商户编号|商户号|merchant id", "[A-Z0-9][A-Z0-9-]{5,31}"));
        rules.add(context("CN_PAYMENT_TRANSACTION_CONTEXT", "支付交易号", "financial", 87,
                "支付交易号|支付流水号|交易订单号|支付订单号", IDENTIFIER));
        rules.add(context("CN_BANK_TRANSACTION_CONTEXT", "银行交易流水号", "financial", 87,
                "银行流水号|交易流水号|回单编号|银行回单号", IDENTIFIER));
        rules.add(context("CN_LOAN_ACCOUNT_CONTEXT", "贷款账号或借据号", "financial", 88,
                "贷款账号|贷款账户|借据号|借款编号|贷款合同编号", IDENTIFIER));
        rules.add(context("CN_CREDIT_REPORT_CONTEXT", "个人征信报告编号", "financial", 88,
                "征信报告编号|信用报告编号|个人信用报告编号|查询授权编号", IDENTIFIER));
        rules.add(context("CN_SECURITIES_ACCOUNT_CONTEXT", "证券账户", "financial", 88,
                "证券账户|证券账号|股东账号|资金账号", "[A-Z0-9][A-Z0-9-]{5,29}"));
        rules.add(context("CN_FUND_ACCOUNT_CONTEXT", "基金账户", "financial", 86,
                "基金账户|基金账号|基金交易账号|TA账号", "[A-Z0-9][A-Z0-9-]{5,29}"));
        rules.add(context("CN_FUTURES_ACCOUNT_CONTEXT", "期货账户", "financial", 86,
                "期货账户|期货账号|期货资金账号", "[A-Z0-9][A-Z0-9-]{5,29}"));
        rules.add(context("CN_INSURANCE_POLICY_CONTEXT", "保险单号", "financial", 88,
                "保险单号|保单号|投保单号|保险合同编号", IDENTIFIER));
        rules.add(context("CN_INSURANCE_CLAIM_CONTEXT", "保险理赔或报案编号", "financial", 87,
                "理赔编号|理赔号|保险报案号|赔案号", IDENTIFIER));
        rules.add(context("CN_TAXPAYER_ID_CONTEXT", "纳税人识别号", "financial", 93,
                "纳税人识别号|税务登记号|税号", "[0-9A-HJ-NPQRTUWXY]{15,20}"));
        rules.add(context("CN_ORGANIZATION_CODE_CONTEXT", "组织机构代码", "organization", 90,
                "组织机构代码|机构代码", "[A-Z0-9]{8}-?[A-Z0-9]"));
        rules.add(context("CN_TAX_INVOICE_CODE_CONTEXT", "发票代码字段", "financial", 87,
                "发票代码", "\\d{10,12}"));
        rules.add(context("CN_TAX_INVOICE_NUMBER_CONTEXT", "发票号码字段", "financial", 87,
                "发票号码|发票号", "\\d{8,20}"));
        rules.add(context("CN_SALARY_FIELD_CONTEXT", "工资、薪酬或奖金字段", "employment", 75,
                "工资|薪酬|薪资|月薪|年薪|奖金|实发金额|应发金额", "(?:人民币|RMB|￥|¥)?[0-9][0-9,]*(?:\\.\\d{1,2})?"));
        rules.add(context("CN_PROVIDENT_FUND_ACCOUNT_CONTEXT", "住房公积金账号", "financial", 88,
                "公积金账号|住房公积金账户|公积金个人账号", ACCOUNT));
        rules.add(context("CN_SOCIAL_INSURANCE_ACCOUNT_CONTEXT", "社会保险个人编号", "financial", 87,
                "社保个人编号|社会保险个人编号|养老保险个人编号", IDENTIFIER));
        rules.add(context("CN_PENSION_ACCOUNT_CONTEXT", "养老金或年金账户", "financial", 86,
                "养老金账户|企业年金账户|职业年金账户", ACCOUNT));
        rules.add(context("CN_DIGITAL_WALLET_CONTEXT", "数字货币或虚拟资产钱包地址", "financial", 85,
                "数字人民币钱包|数字货币钱包|虚拟资产钱包|wallet address", "[A-Z0-9][A-Z0-9:_-]{15,127}"));

        // Network, device, credential and local-path data.
        rules.add(direct("IPV6_ADDRESS", "IPv6地址", "network_security", 93,
                "(?<![0-9A-F:])(?:[0-9A-F]{0,4}:){2,7}[0-9A-F]{0,4}(?![0-9A-F:])", Validators::ipv6));
        rules.add(direct("IPV4_CIDR", "IPv4网段CIDR", "network_security", 91,
                "(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}/(?:[0-9]|[12]\\d|3[0-2])(?![0-9])", Validators::ipv4Cidr));
        rules.add(direct("IPV6_CIDR", "IPv6网段CIDR", "network_security", 91,
                "(?<![0-9A-F:])(?:[0-9A-F]{0,4}:){2,7}[0-9A-F]{0,4}/(?:\\d|[1-9]\\d|1[01]\\d|12[0-8])(?![0-9])", Validators::ipv6Cidr));
        rules.add(direct("MAC_ADDRESS_DOTTED", "点分格式MAC地址", "network_security", 92,
                "(?<![0-9A-F])(?:[0-9A-F]{4}\\.){2}[0-9A-F]{4}(?![0-9A-F])"));
        rules.add(context("DOMAIN_NAME_CONTEXT", "域名字段", "network_security", 84,
                "域名|domain(?: name)?|站点域名", "(?:[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?\\.)+[A-Z]{2,63}"));
        rules.add(context("HOSTNAME_CONTEXT", "主机名字段", "network_security", 83,
                "主机名|服务器名|hostname|server name", "[A-Z0-9][A-Z0-9._-]{1,252}"));
        rules.add(rule("URL_QUERY_SECRET", "网址查询参数中的凭据", "network_security", 99,
                "(?:[?&](?:token|access_token|api_key|apikey|secret|password|passwd|session|sid)=)([^&#\\s]{4,512})", 1));
        rules.add(context("EMAIL_MESSAGE_ID_CONTEXT", "邮件消息ID", "network_security", 82,
                "Message-ID|邮件消息ID|邮件唯一标识", "<?[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+>?"));
        rules.add(context("IMEI_CONTEXT", "移动设备IMEI", "network_security", 98,
                "IMEI(?:1|2)?|设备IMEI", "\\d{15}", Validators::imei));
        rules.add(context("IMSI_CONTEXT", "移动用户IMSI", "network_security", 89,
                "IMSI|国际移动用户识别码", "\\d{15}"));
        rules.add(context("ICCID_CONTEXT", "SIM卡ICCID", "network_security", 98,
                "ICCID|SIM卡号|集成电路卡识别码", "\\d{19,20}", Validators::iccid));
        rules.add(context("DEVICE_SERIAL_CONTEXT", "设备序列号", "network_security", 84,
                "设备序列号|设备SN|序列号|serial number|device id", IDENTIFIER));
        rules.add(context("WIFI_SSID_CONTEXT", "无线网络SSID", "network_security", 80,
                "Wi-?Fi名称|无线网络名称|SSID", "[^，。；;\\r\\n]{1,32}"));
        rules.add(context("VPN_ACCOUNT_CONTEXT", "VPN账户", "network_security", 88,
                "VPN账号|VPN账户|VPN用户名|vpn user", "[A-Z0-9._@-]{3,128}"));
        rules.add(context("DATABASE_CONNECTION_CONTEXT", "数据库连接地址", "network_security", 96,
                "数据库连接|连接字符串|JDBC URL|database url|connection string", "[^，；;\\r\\n]{8,300}"));
        rules.add(context("API_KEY_CONTEXT", "API Key字段", "network_security", 99,
                "API[ _-]?Key|接口密钥|应用密钥|AppSecret|ClientSecret", "[A-Z0-9_./+\\-=]{8,512}"));
        rules.add(context("ACCESS_TOKEN_CONTEXT", "访问令牌字段", "network_security", 99,
                "Access[ _-]?Token|访问令牌|认证令牌|Bearer", "[A-Z0-9_./+\\-=]{8,2048}"));
        rules.add(direct("JWT_TOKEN", "JWT令牌", "network_security", 99,
                "(?<![A-Z0-9_-])eyJ[A-Z0-9_-]{5,}\\.eyJ[A-Z0-9_-]{5,}\\.[A-Z0-9_-]{8,}(?![A-Z0-9_-])"));
        rules.add(context("PASSWORD_CONTEXT", "密码或口令字段", "network_security", 100,
                "密码|口令|登录密码|交易密码|password|passwd|pwd", "[^\\s，。；;]{4,256}"));
        rules.add(direct("WINDOWS_USER_PATH", "Windows用户目录路径", "network_security", 82,
                "(?<![A-Z0-9_])[A-Z]:\\\\Users\\\\[^\\\\\\r\\n]{1,80}(?:\\\\[^\\r\\n:*?\"<>|]{1,120})*"));
        rules.add(direct("UNC_PATH", "UNC网络共享路径", "network_security", 84,
                "\\\\\\\\[A-Z0-9._-]+\\\\[^\\r\\n:*?\"<>|]{1,120}(?:\\\\[^\\r\\n:*?\"<>|]{1,120})*"));
        rules.add(context("GPS_COORDINATES_CONTEXT", "经纬度坐标", "geography_location", 86,
                "经纬度|坐标|GPS|longitude|latitude|纬度|经度", "[-+]?\\d{1,3}(?:\\.\\d{3,8})?[,， ]+[-+]?\\d{1,3}(?:\\.\\d{3,8})?", Validators::coordinates));

        // Administrative geography, roads and address-derived quasi-identifiers.
        rules.add(context("CN_ADMIN_DIVISION_CODE_CONTEXT", "中国大陆行政区划代码", "geography_location", 91,
                "行政区划代码|区划代码|统计用区划代码|城乡划分代码", "\\d{2}(?:\\d{2}(?:\\d{2}(?:\\d{3})?)?)?",
                Validators::chineseAdministrativeDivisionCode));
        rules.add(direct("CN_ROAD_ADDRESS", "中国大陆道路门牌地址", "geography_location", 88,
                "[\\p{IsHan}]{2,24}(?:大道|公路|胡同|路|街|巷|弄)\\d{1,6}号(?:[A-Z0-9\\p{IsHan}-]{0,30})"));
        rules.add(context("CN_BUILDING_ROOM_CONTEXT", "楼栋、单元及房间号", "geography_location", 82,
                "楼栋房间|房间号|门牌号|房号|室号", "[A-Z0-9\\p{IsHan}-]{1,30}(?:栋|幢|座)?[A-Z0-9\\p{IsHan}-]{0,20}(?:单元)?[A-Z0-9\\p{IsHan}-]{0,20}(?:室|号)"));
        rules.add(direct("GLOBAL_CONTINENT", "洲或大陆名称", "geography_location", 64,
                "(?:亚洲|欧洲|非洲|北美洲|南美洲|大洋洲|南极洲|欧亚大陆|"
                        + "(?<![A-Z])(?:Asia|Europe|Africa|North America|South America|Oceania|Antarctica)(?![A-Z]))"));
        rules.add(context("CN_BIRTHPLACE_CONTEXT", "出生地", "geography_location", 84,
                "出生地|出生地点", HAN_VALUE));
        rules.add(context("CN_NATIVE_PLACE_CONTEXT", "籍贯", "geography_location", 84,
                "籍贯|祖籍", HAN_VALUE));
        rules.add(context("CN_REGISTERED_RESIDENCE_CONTEXT", "户籍地址", "geography_location", 95,
                "户籍地址|户口所在地|户籍所在地", HAN_VALUE));
        rules.add(context("CN_CURRENT_RESIDENCE_CONTEXT", "现居住地址", "geography_location", 95,
                "现住址|现居住地|现居地址|经常居住地", HAN_VALUE));
        rules.add(context("CN_DELIVERY_ADDRESS_CONTEXT", "送达或邮寄地址", "geography_location", 95,
                "送达地址|邮寄地址|收件地址|通信地址", HAN_VALUE));
        rules.add(context("CN_PROPERTY_ADDRESS_CONTEXT", "房屋或不动产坐落", "property", 95,
                "房屋坐落|不动产坐落|物业地址|涉案房产地址", HAN_VALUE));
        rules.add(context("CN_WORKPLACE_ADDRESS_CONTEXT", "工作单位地址", "employment", 94,
                "工作单位地址|单位地址|办公地址", HAN_VALUE));
        rules.add(context("CN_SCHOOL_ADDRESS_CONTEXT", "学校地址", "education", 94,
                "学校地址|就读学校地址|院校地址", HAN_VALUE));
        rules.add(context("CN_GEO_GRID_CODE_CONTEXT", "地理网格编码", "geography_location", 80,
                "网格编码|地理网格码|社区网格编号", IDENTIFIER));

        // Legal case, law-enforcement and participant fields.
        rules.add(context("CN_COURT_NAME_CONTEXT", "人民法院名称", "legal_case", 83,
                "受理法院|审理法院|执行法院|管辖法院|人民法院", "[\\p{IsHan}]{2,40}人民法院"));
        rules.add(context("CN_PROCURATORATE_NAME_CONTEXT", "人民检察院名称", "legal_case", 83,
                "检察机关|承办检察院|人民检察院", "[\\p{IsHan}]{2,40}人民检察院"));
        rules.add(context("CN_POLICE_STATION_CONTEXT", "公安机关或派出所名称", "legal_case", 82,
                "公安机关|办案单位|派出所", "[\\p{IsHan}]{2,50}(?:公安局|公安分局|派出所)"));
        rules.add(context("CN_DETENTION_CENTER_CONTEXT", "看守所或拘留所名称", "legal_case", 82,
                "羁押场所|看守所|拘留所", "[\\p{IsHan}]{2,50}(?:看守所|拘留所)"));
        rules.add(context("CN_PRISON_CONTEXT", "监狱名称", "legal_case", 82,
                "服刑监狱|关押监狱|监狱", "[\\p{IsHan}]{2,50}监狱"));
        rules.add(context("CN_CASE_FILE_NUMBER_CONTEXT", "案卷或卷宗编号", "legal_case", 90,
                "案卷号|卷宗号|卷宗编号|档案号|案卷编号", IDENTIFIER));
        rules.add(context("CN_POLICE_CASE_NUMBER_CONTEXT", "公安案件或接报案编号", "legal_case", 90,
                "公安案号|接报案编号|受案登记号|立案编号|案件编号", IDENTIFIER));
        rules.add(context("CN_PROCURATOR_CASE_NUMBER_CONTEXT", "检察机关案件编号", "legal_case", 90,
                "检察案号|审查起诉案号|检察案件编号", IDENTIFIER));
        rules.add(context("CN_EXECUTION_CASE_NUMBER_CONTEXT", "执行案件编号", "legal_case", 90,
                "执行案号|执行案件编号|执行依据文号", IDENTIFIER));
        rules.add(context("CN_BANKRUPTCY_CASE_NUMBER_CONTEXT", "破产案件或管理人编号", "legal_case", 87,
                "破产案号|破产案件编号|管理人案件编号", IDENTIFIER));
        rules.add(context("CN_ARBITRATION_CASE_NUMBER_CONTEXT", "仲裁案件编号", "legal_case", 88,
                "仲裁案号|仲裁案件编号|仲裁受理号", IDENTIFIER));
        rules.add(context("CN_MEDIATION_CASE_NUMBER_CONTEXT", "调解案件编号", "legal_case", 86,
                "调解案号|调解案件编号|人民调解编号", IDENTIFIER));
        rules.add(context("CN_LEGAL_AID_CASE_NUMBER_CONTEXT", "法律援助案件编号", "legal_case", 86,
                "法律援助案号|法律援助案件编号|法援编号", IDENTIFIER));
        rules.add(context("CN_EVIDENCE_NUMBER_CONTEXT", "证据或物证编号", "legal_case", 87,
                "证据编号|物证编号|检材编号|扣押物编号|涉案物品编号", IDENTIFIER));
        rules.add(context("CN_DOSSIER_BARCODE_CONTEXT", "电子卷宗条码", "legal_case", 88,
                "卷宗条码|案卷条码|电子卷宗编号|卷宗二维码编号", IDENTIFIER));
        rules.add(context("CN_JUDICIAL_APPRAISAL_NUMBER_CONTEXT", "司法鉴定文书编号", "legal_case", 88,
                "鉴定书编号|司法鉴定编号|鉴定意见书编号|检验报告编号", IDENTIFIER));
        rules.add(context("CN_ENFORCEMENT_TARGET_CONTEXT", "执行标的或债务金额", "legal_case", 78,
                "执行标的|债务金额|欠款金额|赔偿金额", "(?:人民币|RMB|￥|¥)?[0-9][0-9,]*(?:\\.\\d{1,2})?"));
        rules.add(context("CN_PARTY_NAME_CONTEXT", "案件当事人姓名", "legal_case", 92,
                "当事人|原告|被告|申请人|被申请人|上诉人|被上诉人|申诉人|债权人|债务人", PERSON));
        rules.add(context("CN_AGENT_NAME_CONTEXT", "代理人或辩护人姓名", "legal_case", 90,
                "代理人|委托代理人|诉讼代理人|辩护人|法律顾问", PERSON));
        rules.add(context("CN_JUDGE_CLERK_NAME_CONTEXT", "审判人员或书记员姓名", "legal_case", 90,
                "审判长|审判员|人民陪审员|法官助理|书记员|执行员", PERSON));
        rules.add(context("CN_WITNESS_NAME_CONTEXT", "证人或鉴定人姓名", "legal_case", 90,
                "证人|鉴定人|勘验人|翻译人员", PERSON));
        rules.add(context("CN_VICTIM_NAME_CONTEXT", "被害人或受害人姓名", "legal_case", 92,
                "被害人|受害人|死者|伤者", PERSON));
        rules.add(context("CN_CONTACT_PERSON_CONTEXT", "联系人姓名", "contact_location", 87,
                "联系人|收件人|紧急联系人|经办人|负责人", PERSON));

        // Real estate, land, utilities, vehicle and traffic identifiers.
        rules.add(context("CN_PROPERTY_UNIT_NUMBER_CONTEXT", "不动产单元号", "property", 92,
                "不动产单元号|不动产单元代码", "\\d{12}[A-Z]{2}\\d{5}[A-Z]\\d{8}"));
        rules.add(context("CN_HOUSE_CERT_NUMBER_CONTEXT", "房屋所有权证号", "property", 89,
                "房屋所有权证号|房权证号|房产证号", IDENTIFIER));
        rules.add(context("CN_LAND_CERT_NUMBER_CONTEXT", "土地使用权证号", "property", 89,
                "土地使用权证号|国有土地使用证号|土地证号", IDENTIFIER));
        rules.add(context("CN_HOMESTEAD_CERT_CONTEXT", "宅基地或农村产权证号", "property", 88,
                "宅基地证号|农村宅基地批准书编号|农村产权证号", IDENTIFIER));
        rules.add(context("CN_CADASTRAL_NUMBER_CONTEXT", "地籍号", "property", 88,
                "地籍号|地籍编号|宗地代码", IDENTIFIER));
        rules.add(context("CN_PARCEL_NUMBER_CONTEXT", "宗地号或地块编号", "property", 88,
                "宗地号|宗地编号|地块编号|土地编号", IDENTIFIER));
        rules.add(context("CN_BUILDING_NUMBER_CONTEXT", "楼栋或建筑物编号", "property", 80,
                "楼栋号|楼号|幢号|建筑物编号", "[A-Z0-9\\p{IsHan}-]{1,30}"));
        rules.add(context("CN_ROOM_NUMBER_CONTEXT", "房屋房间编号", "property", 80,
                "房号|室号|房屋编号|单元房号", "[A-Z0-9\\p{IsHan}-]{1,30}"));
        rules.add(context("CN_LEASE_CONTRACT_CONTEXT", "房屋租赁合同编号", "property", 86,
                "租赁合同编号|房屋租赁备案号|租赁备案编号", IDENTIFIER));
        rules.add(context("CN_MORTGAGE_REGISTRATION_CONTEXT", "抵押登记或证明编号", "property", 88,
                "抵押登记号|抵押证明号|不动产登记证明号", IDENTIFIER));
        rules.add(context("CN_VEHICLE_REGISTRATION_CERT_CONTEXT", "机动车登记证书编号", "vehicle_property", 88,
                "机动车登记证书编号|车辆登记证编号|登记证书编号", IDENTIFIER));
        rules.add(context("CN_TRAFFIC_ACCIDENT_CONTEXT", "交通事故或违法编号", "vehicle_property", 87,
                "事故编号|交通事故编号|违法编号|处罚决定书编号", IDENTIFIER));
        rules.add(context("CN_ETC_ACCOUNT_CONTEXT", "ETC账户或电子标签编号", "vehicle_property", 85,
                "ETC账号|ETC账户|电子标签编号|OBU编号", IDENTIFIER));
        rules.add(context("CN_PARKING_SPACE_CONTEXT", "车位编号", "property", 78,
                "车位号|停车位编号|车库编号", "[A-Z0-9\\p{IsHan}-]{1,30}"));
        rules.add(context("CN_UTILITY_ACCOUNT_CONTEXT", "水电燃气或通信缴费户号", "property", 82,
                "水费户号|电费户号|燃气户号|供暖户号|宽带账号|有线电视户号", IDENTIFIER));
        rules.add(context("CN_METER_NUMBER_CONTEXT", "水电燃气表号", "property", 80,
                "水表号|电表号|燃气表号|计量表编号", IDENTIFIER));

        return List.copyOf(rules);
    }

    private static RuleDefinition direct(String id, String label, String category, int priority,
                                         String regex) {
        return direct(id, label, category, priority, regex, Validators::always);
    }

    private static RuleDefinition direct(String id, String label, String category, int priority,
                                         String regex, RuleValidator validator) {
        return rule(id, label, category, priority, regex, 0, validator);
    }

    private static RuleDefinition context(String id, String label, String category, int priority,
                                          String labelRegex, String valueRegex) {
        return context(id, label, category, priority, labelRegex, valueRegex, Validators::always);
    }

    private static RuleDefinition context(String id, String label, String category, int priority,
                                          String labelRegex, String valueRegex, RuleValidator validator) {
        return rule(id, label, category, priority,
                "(?:" + labelRegex + ")" + FIELD_SEPARATOR + "(" + valueRegex + ")", 1, validator);
    }

    private static RuleDefinition rule(String id, String label, String category, int priority,
                                       String regex, int captureGroup) {
        return rule(id, label, category, priority, regex, captureGroup, Validators::always);
    }

    private static RuleDefinition rule(String id, String label, String category, int priority,
                                       String regex, int captureGroup, RuleValidator validator) {
        return new RuleDefinition(id, label, category, priority,
                RedactionPattern.reviewed(regex, FLAGS), captureGroup, validator);
    }
}
