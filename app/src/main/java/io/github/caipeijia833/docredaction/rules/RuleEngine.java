/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RuleEngine {
    private static final int MAX_CUSTOM_RULES = 100;
    private static final int MAX_RULE_HISTORY = 20;
    private static final int MAX_RULE_TEST_TEXT = 20_000;
    private static final Pattern NESTED_UNBOUNDED_QUANTIFIER = Pattern.compile(
            "\\((?:\\\\.|[^)])*[+*](?:\\\\.|[^)])*\\)\\s*(?:[+*]|\\{)");
    private static final Pattern NUMERIC_BACK_REFERENCE = Pattern.compile("\\\\[1-9]");
    private final List<RuleDefinition> baseRules;
    private final Path settingsFile;
    private final byte[] pseudonymKey;
    private final ChineseAdministrativeDivisionLexicon administrativeDivisionLexicon;
    private volatile Set<String> disabledRuleIds = Set.of();
    private volatile List<String> blacklist = List.of();
    private volatile List<String> whitelist = List.of();
    private volatile List<RuleDefinition> customRules = List.of();
    private final AtomicLong version = new AtomicLong(1);

    public RuleEngine(List<RuleDefinition> rules) {
        this(rules, null, null);
    }

    private RuleEngine(List<RuleDefinition> rules, Path settingsFile) {
        this(rules, settingsFile, null);
    }

    private RuleEngine(List<RuleDefinition> rules, Path settingsFile, byte[] pseudonymKey) {
        this.baseRules = List.copyOf(rules);
        this.settingsFile = settingsFile;
        this.pseudonymKey = pseudonymKey == null ? null : pseudonymKey.clone();
        this.administrativeDivisionLexicon = ChineseAdministrativeDivisionLexicon.instance();
        if (settingsFile != null) {
            if (Files.isRegularFile(settingsFile)) {
                loadSettings();
            } else {
                try {
                    saveSettings();
                } catch (IOException ex) {
                    throw new IllegalStateException("无法初始化本地规则配置", ex);
                }
            }
        }
    }

    public static RuleEngine createDefault() {
        int unicode = Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE;
        List<RuleDefinition> rules = new ArrayList<>();
        rules.add(rule("CN_RESIDENT_ID", "居民身份证号", "identity", 100,
                "(?<![0-9A-Za-z])[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9A-Za-z])",
                0, Validators::chineseResidentId));
        rules.add(rule("CN_UNIFIED_SOCIAL_CREDIT_CODE", "统一社会信用代码", "organization", 98,
                "(?<![0-9A-Z])[0-9A-HJ-NPQRTUWXY]{18}(?![0-9A-Z])",
                0, Validators::unifiedSocialCreditCode));
        rules.add(rule("BANK_CARD_NUMBER", "银行卡号", "financial", 96,
                "(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)", 0, Validators::luhn));
        rules.add(rule("EMAIL_ADDRESS", "电子邮箱", "contact_location", 94,
                "(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}(?![A-Z0-9._%+-])",
                0, Validators::always, unicode));
        rules.add(rule("CN_MOBILE_PHONE", "手机号码", "contact_location", 92,
                "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)", 0, Validators::always));
        rules.add(rule("IPV4_ADDRESS", "IPv4地址", "network_security", 90,
                "(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9.])", 0, Validators::ipv4));
        rules.add(rule("MAC_ADDRESS", "MAC地址", "network_security", 90,
                "(?<![0-9A-F])(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}(?![0-9A-F])",
                0, Validators::always, unicode));
        rules.add(rule("VEHICLE_IDENTIFICATION_NUMBER", "车辆识别代号VIN", "vehicle_property", 88,
                "(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])", 0,
                Validators::vinContext, unicode));
        rules.add(rule("CN_LICENSE_PLATE", "车辆号牌", "vehicle_property", 87,
                "(?<![A-Z0-9])[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-Z][A-HJ-NP-Z0-9]{5,6}(?![A-Z0-9])",
                0, Validators::always, unicode));
        rules.add(rule("CN_JUDICIAL_CASE_NUMBER", "司法案号", "legal_case", 86,
                "[（(]\\d{4}[）)][\\p{IsHan}]{1,4}\\d{1,6}[\\p{IsHan}]{1,4}\\d+号",
                0, Validators::always));
        rules.add(rule("PASSPORT_NUMBER", "护照号码", "identity", 85,
                "(?<![A-Z0-9])(?:[EG]\\d{8}|[A-Z]\\d{7,8})(?![A-Z0-9])", 0,
                Validators.context("护照", "passport"), unicode));
        rules.add(rule("URL", "网址", "network_security", 82,
                "https?://[^\\s<>()\\[\\]{}\"'，。；;]+", 0, Validators::always, unicode));
        rules.add(rule("TELEPHONE_NUMBER", "固定电话及传真", "contact_location", 80,
                "(?<!\\d)(?:0\\d{2,3}[- ]?)?\\d{7,8}(?:[-转 ]\\d{1,6})?(?!\\d)",
                0, Validators.context("电话", "传真", "联系方式", "tel")));
        rules.add(rule("POSTAL_CODE", "邮政编码", "contact_location", 76,
                "(?<!\\d)[1-9]\\d{5}(?!\\d)", 0, Validators.context("邮编", "邮政编码")));
        rules.add(rule("INVOICE_CODE_NUMBER", "发票代码及号码", "financial", 75,
                "(?<!\\d)\\d{8,20}(?!\\d)", 0, Validators.context("发票代码", "发票号码", "发票号")));
        rules.add(rule("CONTRACT_NUMBER", "合同编号", "business", 74,
                "(?:合同编号|合同号)[：:\\s]*([A-Z0-9][A-Z0-9_./-]{3,40})", 1, Validators::always, unicode));
        rules.add(rule("QQ_ID", "QQ号", "contact_location", 72,
                "(?:QQ|qq)[号：:\\s]*([1-9]\\d{4,11})", 1, Validators::always));
        rules.add(rule("WECHAT_ID", "微信号", "contact_location", 72,
                "(?:微信号|微信|WeChat)[：:\\s]*([A-Z][-_A-Z0-9]{5,19})", 1, Validators::always, unicode));
        rules.add(rule("ARABIC_NUMERAL_AMOUNT", "数字金额", "financial", 70,
                "(?:人民币|RMB|￥|¥)[：:\\s]*([0-9][0-9,]*(?:\\.\\d{1,2})?)", 1, Validators::always, unicode));
        rules.add(rule("CN_UPPERCASE_AMOUNT", "中文大写金额", "financial", 69,
                "(?:人民币|金额|价款)[：:\\s]*([零壹贰叁肆伍陆柒捌玖拾佰仟万亿兆圆元角分整]{2,40})",
                1, Validators::always));
        rules.add(rule("STOCK_CODE", "股票及证券代码", "financial", 68,
                "(?:股票代码|证券代码|股票)[：:\\s]*((?:[0368]\\d{5}|[A-Z]{1,5})(?:\\.(?:SH|SZ|BJ|HK|US))?)",
                1, Validators::always, unicode));
        rules.add(rule("HK_MACAO_TAIWAN_PERMIT", "港澳台通行证及居住证", "identity", 67,
                "(?<![A-Z0-9])[HMLWC]\\s?\\d{8,10}(?![A-Z0-9])", 0,
                Validators.context("通行证", "居住证", "回乡证", "台胞证"), unicode));
        rules.add(rule("LEGACY_BUSINESS_REGISTRATION", "旧版工商注册号", "organization", 66,
                "(?:工商注册号|营业执照注册号|企业注册号|注册号)[：:\\s]*([0-9][0-9 -]{13,20}[0-9])",
                1, Validators::always));
        rules.add(rule("REAL_ESTATE_CERTIFICATE", "不动产权证书号", "property", 65,
                "(?:不动产权证书号|不动产权证号|房产证号|产权证号)[：:\\s]*([^，。；;\\r\\n]{4,50})",
                1, Validators::always));
        rules.add(rule("CN_DATE", "日期", "quasi_identifier", 60,
                "(?<!\\d)(?:19|20)\\d{2}[年./-](?:0?[1-9]|1[0-2])[月./-](?:0?[1-9]|[12]\\d|3[01])日?(?!\\d)",
                0, Validators::always));
        rules.add(rule("CN_PERSON_NAME", "中文姓名", "identity", 58,
                "(?:姓名|当事人|申请人|原告|被告人?|证人)[：:\\s]{0,3}([\\p{IsHan}·]{2,8})",
                1, Validators::always));
        rules.add(rule("POSTAL_ADDRESS", "详细地址", "contact_location", 90,
                "(?:户籍地址|户籍地|联系地址|地址|住址|住所)[：:\\s]{0,3}([^，。；;\\r\\n]{6,80})",
                1, Validators::always));
        rules.add(rule("ORGANIZATION_NAME", "单位及机构名称", "organization", 54,
                "(?:单位|工作单位|所属机构|律所)[：:\\s]{0,3}([^，。；;\\r\\n]{3,60})",
                1, Validators::always));

        // Global banking, contact and identity rules. Standalone identifiers are only enabled
        // where a checksum and sufficiently distinctive structure are available. Ambiguous
        // account/property/vehicle values require a nearby field label captured by the pattern.
        rules.add(rule("IBAN", "国际银行账号IBAN", "financial", 99,
                "(?<![A-Z0-9])[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]){11,30}(?![A-Z0-9])",
                0, Validators::iban, unicode));
        rules.add(rule("US_SSN", "美国社会安全号码SSN", "identity", 97,
                "(?<!\\d)\\d{3}[- ]\\d{2}[- ]\\d{4}(?!\\d)", 0, Validators::usSsn));
        rules.add(rule("EN_DATE_OF_BIRTH", "英文出生日期字段", "quasi_identifier", 93,
                "(?:date of birth|birth date|dob)[：:=\\s]{0,5}((?:19|20)\\d{2}[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12]\\d|3[01])|(?:0?[1-9]|[12]\\d|3[01])[-/.](?:0?[1-9]|1[0-2])[-/.](?:19|20)\\d{2})",
                1, Validators::always, unicode));
        rules.add(rule("INTERNATIONAL_PHONE", "国际电话号码", "contact_location", 91,
                "(?:phone|telephone|mobile|cell|contact number|tel)[：:=\\s]{0,5}(\\+?[0-9](?:[0-9 ()-]{5,22}[0-9]))",
                1, Validators::internationalPhone, unicode));
        rules.add(rule("SWIFT_BIC_CONTEXT", "SWIFT或BIC代码字段", "financial", 84,
                "(?:swift|bic)(?: code)?[：:=\\s]{0,5}([A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)",
                1, Validators::always, unicode));
        rules.add(rule("GENERIC_BANK_ACCOUNT_CONTEXT", "银行账户字段", "financial", 83,
                "(?:bank account|account number|account no\\.?|beneficiary account|银行账号|账户号码)[：:=\\s]{0,5}([A-Z0-9](?:[ A-Z0-9-]{4,32})[A-Z0-9])",
                1, Validators::always, unicode));
        rules.add(rule("EN_PERSON_NAME", "英文姓名字段", "identity", 57,
                "(?:full name|legal name|customer name|owner name|borrower name|applicant name|plaintiff name|defendant name|witness name|insured name)[：:=\\s]{0,5}([A-Z][A-Z'’.-]{1,39}(?:\\s+[A-Z][A-Z'’.-]{1,39}){1,4})",
                1, Validators::always, unicode));
        rules.add(rule("EN_POSTAL_ADDRESS", "英文详细地址字段", "contact_location", 55,
                "(?:residential address|mailing address|home address|property address|service address|address)[：:=\\s]{0,5}([^\\r\\n;；]{6,160})",
                1, Validators::always, unicode));
        rules.add(rule("EN_ORGANIZATION_NAME", "英文机构名称字段", "organization", 53,
                "(?:(?:employer|company|organization|organisation|law firm)(?:[ ]+name)?[：:=][ \\t]{0,4}|"
                        + "(?:employer|company|organization|organisation|law firm)[ ]+name[ \\t]+)"
                        + "([^\\r\\n;；]{3,100})",
                1, Validators::always, unicode));

        // United States and Canada.
        rules.add(rule("US_EIN_CONTEXT", "美国雇主识别号码EIN", "organization", 89,
                "(?:ein|employer identification number|federal tax id)[：:=\\s]{0,5}(\\d{2}-\\d{7})",
                1, Validators::always, unicode));
        rules.add(rule("US_ITIN_CONTEXT", "美国个人纳税识别号码ITIN", "identity", 89,
                "(?:itin|individual taxpayer identification number)[：:=\\s]{0,5}(9\\d{2}[- ](?:7\\d|8[0-8])[- ]\\d{4})",
                1, Validators::always, unicode));
        rules.add(rule("US_ABA_ROUTING", "美国ABA银行路由号码", "financial", 88,
                "(?<!\\d)\\d{9}(?!\\d)", 0, Validators::abaRouting));
        rules.add(rule("US_DRIVER_LICENSE_CONTEXT", "美国驾驶证号码字段", "identity", 78,
                "(?:driver'?s license|driver license|dl number|license no\\.?)[：:=\\s]{0,5}([A-Z0-9][A-Z0-9 -]{3,24})",
                1, Validators::always, unicode));
        rules.add(rule("CANADA_SIN_CONTEXT", "加拿大社会保险号码SIN", "identity", 90,
                "(?:sin|social insurance number)[：:=\\s]{0,5}((?:\\d[ -]?){8}\\d)",
                1, Validators::canadianSin, unicode));
        rules.add(rule("CANADA_BANK_CONTEXT", "加拿大银行账户字段", "financial", 81,
                "(?:transit|institution|canadian bank account|account number)[：:=\\s]{0,5}([0-9][0-9 -]{4,24}[0-9])",
                1, Validators::always, unicode));

        // United Kingdom and selected European national identifiers.
        rules.add(rule("UK_NINO", "英国国家保险号码NINO", "identity", 92,
                "(?<![A-Z0-9])[A-Z]{2}[ -]?\\d{2}[ -]?\\d{2}[ -]?\\d{2}[ -]?[A-D]?(?![A-Z0-9])",
                0, Validators::ukNationalInsurance, unicode));
        rules.add(rule("UK_NHS_NUMBER_CONTEXT", "英国NHS号码", "identity", 90,
                "(?:nhs number|nhs no\\.?)[：:=\\s]{0,5}((?:\\d[ -]?){9}\\d)",
                1, Validators::ukNhs, unicode));
        rules.add(rule("UK_SORT_CODE_ACCOUNT", "英国银行Sort Code及账号", "financial", 88,
                "(?:sort code)[：:=\\s]{0,5}(\\d{2}[- ]?\\d{2}[- ]?\\d{2}(?:[ ,;/]+(?:account|a/c)(?: number| no\\.?)?[：:=\\s]{0,5}\\d{8})?)",
                1, Validators::always, unicode));
        rules.add(rule("DE_TAX_ID_CONTEXT", "德国税务识别号码IdNr", "identity", 89,
                "(?:steueridentifikationsnummer|steuer-id|tax identification number|idnr)[：:=\\s]{0,5}((?:\\d[ ]?){10}\\d)",
                1, Validators::germanTaxId, unicode));
        rules.add(rule("FR_NIR_CONTEXT", "法国社会保障号码NIR", "identity", 89,
                "(?:nir|numéro de sécurité sociale|numero de securite sociale|social security number)[：:=\\s]{0,5}((?:\\d[ ]?){14}\\d)",
                1, Validators::frenchNir, unicode));
        rules.add(rule("ES_DNI_NIE", "西班牙DNI或NIE", "identity", 90,
                "(?<![A-Z0-9])(?:\\d{8}|[XYZ]\\d{7})[- ]?[A-Z](?![A-Z0-9])",
                0, Validators::spanishDniNie, unicode));
        rules.add(rule("IT_CODICE_FISCALE", "意大利税号Codice Fiscale", "identity", 90,
                "(?<![A-Z0-9])[A-Z]{6}\\d{2}[ABCDEHLMPRST]\\d{2}[A-Z]\\d{3}[A-Z](?![A-Z0-9])",
                0, Validators::italianFiscalCode, unicode));
        rules.add(rule("NL_BSN_CONTEXT", "荷兰公民服务号码BSN", "identity", 89,
                "(?:bsn|burgerservicenummer|citizen service number)[：:=\\s]{0,5}(\\d{9})",
                1, Validators::dutchBsn, unicode));
        rules.add(rule("PL_PESEL_CONTEXT", "波兰PESEL号码", "identity", 89,
                "(?:pesel)[：:=\\s]{0,5}(\\d{11})", 1, Validators::polishPesel, unicode));
        rules.add(rule("SE_PERSONNUMMER_CONTEXT", "瑞典个人号码Personnummer", "identity", 88,
                "(?:personnummer|personal identity number)[：:=\\s]{0,5}((?:\\d{2})?\\d{6}[-+ ]?\\d{4})",
                1, Validators::swedishPersonNumber, unicode));
        rules.add(rule("NO_FODSELSNUMMER_CONTEXT", "挪威出生号码Fødselsnummer", "identity", 88,
                "(?:fødselsnummer|fodselsnummer|national identity number)[：:=\\s]{0,5}(\\d{11})",
                1, Validators::norwegianBirthNumber, unicode));
        rules.add(rule("DK_CPR_CONTEXT", "丹麦CPR号码", "identity", 77,
                "(?:cpr|personal identification number)[：:=\\s]{0,5}(\\d{6}[- ]?\\d{4})",
                1, Validators::always, unicode));
        rules.add(rule("FI_HETU", "芬兰个人身份代码HETU", "identity", 90,
                "(?<![A-Z0-9])\\d{6}[+\\-ABCDEFYXWVU]\\d{3}[0-9A-Z](?![A-Z0-9])",
                0, Validators::finnishPersonalCode, unicode));

        // Asia-Pacific national identifiers.
        rules.add(rule("AU_TFN_CONTEXT", "澳大利亚税号TFN", "identity", 89,
                "(?:tfn|tax file number)[：:=\\s]{0,5}((?:\\d[ -]?){7,8}\\d)",
                1, Validators::australianTfn, unicode));
        rules.add(rule("AU_BSB_ACCOUNT", "澳大利亚BSB及银行账号", "financial", 87,
                "(?:bsb)[：:=\\s]{0,5}(\\d{3}[- ]?\\d{3}(?:[ ,;/]+(?:account|a/c)(?: number| no\\.?)?[：:=\\s]{0,5}\\d{5,10})?)",
                1, Validators::always, unicode));
        rules.add(rule("NZ_IRD_CONTEXT", "新西兰IRD号码", "identity", 89,
                "(?:ird number|ird no\\.?)[：:=\\s]{0,5}((?:\\d[ -]?){7,8}\\d)",
                1, Validators::newZealandIrd, unicode));
        rules.add(rule("NZ_BANK_ACCOUNT_CONTEXT", "新西兰银行账号字段", "financial", 82,
                "(?:new zealand bank account|nz bank account|account number)[：:=\\s]{0,5}(\\d{2}[- ]?\\d{4}[- ]?\\d{7}[- ]?\\d{2,3})",
                1, Validators::always, unicode));
        rules.add(rule("JP_MY_NUMBER_CONTEXT", "日本个人编号My Number", "identity", 91,
                "(?:my number|個人番号|マイナンバー)[：:=\\s]{0,5}((?:\\d[ -]?){11}\\d)",
                1, Validators::japanMyNumber, unicode));
        rules.add(rule("KR_RESIDENT_NUMBER", "韩国居民登记号码", "identity", 91,
                "(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)", 0, Validators::koreanResidentNumber));
        rules.add(rule("SG_NRIC_FIN", "新加坡NRIC或FIN", "identity", 92,
                "(?<![A-Z0-9])[STFGM]\\d{7}[A-Z](?![A-Z0-9])", 0, Validators::singaporeNric, unicode));
        rules.add(rule("IN_AADHAAR", "印度Aadhaar号码", "identity", 92,
                "(?<!\\d)[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)", 0, Validators::indiaAadhaarContext));
        rules.add(rule("IN_PAN_CONTEXT", "印度永久账户号码PAN", "identity", 88,
                "(?:pan|permanent account number)[：:=\\s]{0,5}([A-Z]{5}\\d{4}[A-Z])",
                1, Validators::always, unicode));
        rules.add(rule("IN_IFSC_ACCOUNT", "印度IFSC及银行账号", "financial", 87,
                "(?:ifsc)[：:=\\s]{0,5}([A-Z]{4}0[A-Z0-9]{6}(?:[ ,;/]+(?:account|a/c)(?: number| no\\.?)?[：:=\\s]{0,5}[A-Z0-9]{6,20})?)",
                1, Validators::always, unicode));
        rules.add(rule("HK_IDENTITY_CARD", "香港身份证号码", "identity", 92,
                "(?<![A-Z0-9])[A-Z]{1,2}[ ]?\\d{6}[ ]?\\(?[0-9A]\\)?(?![A-Z0-9])",
                0, Validators::hongKongId, unicode));
        rules.add(rule("TW_NATIONAL_ID", "台湾地区身份证号码", "identity", 92,
                "(?<![A-Z0-9])[A-Z][12]\\d{8}(?![A-Z0-9])", 0, Validators::taiwanNationalId, unicode));
        rules.add(rule("MO_BIR_CONTEXT", "澳门居民身份证号码字段", "identity", 82,
                "(?:macau id|macao id|bir|澳門居民身份證|澳门居民身份证)[：:=\\s]{0,5}([157]\\d{6}\\(?\\d\\)?)",
                1, Validators::always, unicode));

        // Vehicle-related identifiers. Generic values require field context because national
        // registration formats overlap heavily with ordinary business codes.
        rules.add(rule("VEHICLE_ENGINE_NUMBER_CONTEXT", "车辆发动机号码字段", "vehicle_property", 86,
                "(?:engine number|engine no\\.?|motor number|发动机号|引擎号码)[：:=\\s]{0,5}([A-Z0-9][A-Z0-9./-]{3,30})",
                1, Validators::always, unicode));
        rules.add(rule("VEHICLE_REGISTRATION_CONTEXT", "车辆登记号码字段", "vehicle_property", 85,
                "(?:vehicle registration|registration number|registration no\\.?|license plate|licence plate|plate number|车牌号|车辆登记号)[：:=\\s]{0,5}([A-Z0-9][A-Z0-9 -]{2,20})",
                1, Validators::always, unicode));
        rules.add(rule("UK_LICENSE_PLATE_CONTEXT", "英国车辆号牌字段", "vehicle_property", 86,
                "(?:registration|number plate|licence plate)[：:=\\s]{0,5}([A-Z]{2}\\d{2}[ ]?[A-Z]{3})",
                1, Validators::always, unicode));
        rules.add(rule("VEHICLE_INSURANCE_POLICY_CONTEXT", "车辆保险单号码字段", "vehicle_property", 83,
                "(?:vehicle insurance|auto insurance|motor insurance|policy number|policy no\\.?|车险保单号)[：:=\\s]{0,5}([A-Z0-9][A-Z0-9./-]{4,40})",
                1, Validators::always, unicode));

        // Real-estate and land identifiers are jurisdiction-specific and often lack public
        // checksums, so every rule below requires an explicit label in the same text unit.
        rules.add(rule("PROPERTY_PARCEL_CONTEXT", "地块或宗地编号字段", "property", 80,
                "(?:parcel number|parcel id|assessor'?s parcel number|apn|cadastral number|cadastral parcel|lot number|地块编号|宗地号|地籍号)[：:=\\s]{0,5}([A-Z0-9][A-Z0-9./ -]{3,50})",
                1, Validators::always, unicode));
        rules.add(rule("LAND_REGISTRY_TITLE_CONTEXT", "土地登记或产权编号字段", "property", 80,
                "(?:land registry title|title number|title no\\.?|deed number|deed no\\.?|property certificate|产权登记号|土地登记号|契据号)[：:=\\s]{0,5}([A-Z0-9][A-Z0-9./ -]{3,50})",
                1, Validators::always, unicode));
        rules.add(rule("UK_UPRN_CONTEXT", "英国唯一物业参考号UPRN", "property", 84,
                "(?:uprn|unique property reference number)[：:=\\s]{0,5}(\\d{1,12})",
                1, Validators::always, unicode));
        rules.add(rule("MORTGAGE_ACCOUNT_CONTEXT", "按揭或抵押贷款账号字段", "property", 81,
                "(?:mortgage account|mortgage loan number|loan account|property loan|按揭账号|抵押贷款编号)[：:=\\s]{0,5}([A-Z0-9][A-Z0-9./ -]{4,40})",
                1, Validators::always, unicode));
        rules.addAll(ChineseMainlandRules.create());
        rules.addAll(ChineseAdministrativeDivisionLexicon.ruleDefinitions());
        return new RuleEngine(rules);
    }

    public static RuleEngine createDefault(Path settingsFile) {
        RuleEngine defaults = createDefault();
        return new RuleEngine(defaults.baseRules, settingsFile.toAbsolutePath().normalize());
    }

    private static RuleDefinition rule(String id, String label, String category, int priority,
            String regex, int group, RuleValidator validator) {
        return rule(id, label, category, priority, regex, group, validator, Pattern.UNICODE_CASE);
    }

    private static RuleDefinition rule(String id, String label, String category, int priority,
            String regex, int group, RuleValidator validator, int flags) {
        return new RuleDefinition(id, label, category, priority,
                RedactionPattern.reviewed(regex, flags), group, validator);
    }

    public List<RuleDefinition> rules() {
        List<RuleDefinition> all = new ArrayList<>(baseRules);
        all.addAll(customRules);
        return Collections.unmodifiableList(all);
    }

    public boolean isEnabled(String id) {
        return !disabledRuleIds.contains(id);
    }

    public long version() {
        return version.get();
    }

    public boolean usesStablePseudonyms() {
        return pseudonymKey != null;
    }

    public RuleEngine withStablePseudonyms(byte[] projectKey) {
        if (projectKey == null || projectKey.length < 16) {
            throw new IllegalArgumentException("项目一致替换密钥无效");
        }
        RuleEngine snapshot = new RuleEngine(baseRules, null, projectKey);
        snapshot.disabledRuleIds = disabledRuleIds;
        snapshot.blacklist = blacklist;
        snapshot.whitelist = whitelist;
        snapshot.customRules = customRules;
        snapshot.version.set(version.get());
        return snapshot;
    }

    public RuleEngine withAdditionalWhitelist(List<String> values) {
        RuleEngine snapshot = new RuleEngine(baseRules, null, pseudonymKey);
        snapshot.disabledRuleIds = disabledRuleIds;
        snapshot.blacklist = blacklist;
        List<String> combined = new ArrayList<>(whitelist);
        if (values != null) {
            combined.addAll(values);
        }
        snapshot.whitelist = sanitizeList(combined, 6_000);
        snapshot.customRules = customRules;
        snapshot.version.set(version.get());
        return snapshot;
    }

    public RuleEngine withSelectedCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return this;
        }
        Set<String> selected = categories.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个脱敏规则分类");
        }
        RuleEngine snapshot = new RuleEngine(baseRules, null, pseudonymKey);
        Set<String> disabled = new HashSet<>(disabledRuleIds);
        rules().stream().filter(rule -> !selected.contains(rule.category().toLowerCase(Locale.ROOT)))
                .map(RuleDefinition::id).forEach(disabled::add);
        snapshot.disabledRuleIds = Set.copyOf(disabled);
        snapshot.blacklist = blacklist;
        snapshot.whitelist = whitelist;
        snapshot.customRules = customRules;
        snapshot.version.set(version.get());
        return snapshot;
    }

    public List<String> blacklist() {
        return blacklist;
    }

    public List<String> whitelist() {
        return whitelist;
    }

    public synchronized void setEnabled(String id, boolean enabled) throws IOException {
        if (rules().stream().noneMatch(rule -> rule.id().equals(id))) {
            throw new IllegalArgumentException("规则不存在");
        }
        Set<String> disabled = new HashSet<>(disabledRuleIds);
        if (enabled) {
            disabled.remove(id);
        } else {
            disabled.add(id);
        }
        disabledRuleIds = Set.copyOf(disabled);
        changed();
    }

    public synchronized void replaceBlacklist(List<String> values) throws IOException {
        blacklist = sanitizeList(values, 1_000);
        changed();
    }

    public synchronized void replaceWhitelist(List<String> values) throws IOException {
        whitelist = sanitizeList(values, 1_000);
        changed();
    }

    public synchronized RuleDefinition addCustomRule(String requestedId, String label, String category,
            int priority, String regex) throws IOException {
        if (customRules.size() >= MAX_CUSTOM_RULES) {
            throw new IllegalArgumentException("自定义规则最多100条");
        }
        String id = normalizeCustomId(requestedId);
        String finalId = id;
        if (rules().stream().anyMatch(rule -> rule.id().equals(finalId))) {
            throw new IllegalArgumentException("规则ID已存在");
        }
        RuleDefinition custom = customRule(finalId, label, category, priority, regex);
        List<RuleDefinition> updated = new ArrayList<>(customRules);
        updated.add(custom);
        customRules = List.copyOf(updated);
        changed();
        return custom;
    }

    public synchronized RuleDefinition updateCustomRule(String requestedId, String label, String category,
            int priority, String regex) throws IOException {
        String id = normalizeCustomId(requestedId);
        int index = -1;
        for (int i = 0; i < customRules.size(); i++) {
            if (customRules.get(i).id().equals(id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("自定义规则不存在");
        }
        RuleDefinition replacement = customRule(id, label, category, priority, regex);
        List<RuleDefinition> updated = new ArrayList<>(customRules);
        updated.set(index, replacement);
        customRules = List.copyOf(updated);
        changed();
        return replacement;
    }

    public List<CustomRuleSpec> customRuleSpecs() {
        return customRules.stream().map(RuleEngine::toSpec).toList();
    }

    public synchronized void importCustomRules(List<CustomRuleSpec> imported, boolean replace) throws IOException {
        if (imported == null || imported.isEmpty()) {
            throw new IllegalArgumentException("导入文件没有自定义规则");
        }
        LinkedHashMap<String, RuleDefinition> merged = new LinkedHashMap<>();
        if (!replace) {
            for (RuleDefinition existing : customRules) {
                merged.put(existing.id(), existing);
            }
        }
        for (CustomRuleSpec specification : imported) {
            if (specification == null) {
                continue;
            }
            String id = normalizeCustomId(specification.id());
            if (baseRules.stream().anyMatch(rule -> rule.id().equals(id))) {
                throw new IllegalArgumentException("导入规则不能覆盖内置规则：" + id);
            }
            RuleDefinition rule = customRule(id, specification.label(), specification.category(),
                    specification.priority(), specification.regex());
            if (!replace && merged.containsKey(id)) {
                throw new IllegalArgumentException("导入规则ID与现有规则重复：" + id);
            }
            merged.put(id, rule);
            if (merged.size() > MAX_CUSTOM_RULES) {
                throw new IllegalArgumentException("自定义规则最多100条");
            }
        }
        if (merged.isEmpty()) {
            throw new IllegalArgumentException("导入文件没有有效的自定义规则");
        }
        customRules = List.copyOf(merged.values());
        Set<String> known = customRules.stream().map(RuleDefinition::id).collect(java.util.stream.Collectors.toSet());
        Set<String> disabled = new HashSet<>(disabledRuleIds);
        disabled.removeIf(id -> id.startsWith("CUSTOM_") && !known.contains(id));
        disabledRuleIds = Set.copyOf(disabled);
        changed();
    }

    public List<RuleTestMatch> testCustomRule(String regex, String sample) {
        RedactionPattern pattern = compileCustomPattern(regex);
        String text = sample == null ? "" : sample;
        if (text.length() > MAX_RULE_TEST_TEXT) {
            throw new IllegalArgumentException("规则测试文本不能超过20000个字符");
        }
        List<RuleTestMatch> matches = new ArrayList<>();
        RedactionPattern.Match matcher = pattern.matcher(text);
        while (matcher.find() && matches.size() < 100) {
            int start = matcher.start();
            int end = matcher.end();
            int contextStart = Math.max(0, start - 24);
            int contextEnd = Math.min(text.length(), end + 24);
            matches.add(new RuleTestMatch(start, end, matcher.group(),
                    text.substring(contextStart, contextEnd)));
        }
        return List.copyOf(matches);
    }

    public List<Long> historyVersions() throws IOException {
        Path directory = historyDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("rules-v[0-9]+\\.properties"))
                    .map(name -> Long.parseLong(name.substring(7, name.length() - 11)))
                    .sorted(Comparator.reverseOrder()).toList();
        }
    }

    public synchronized void rollback(long targetVersion) throws IOException {
        Path directory = historyDirectory();
        Path snapshot = directory == null ? null : directory.resolve("rules-v" + targetVersion + ".properties");
        if (snapshot == null || !Files.isRegularFile(snapshot)) {
            throw new IllegalArgumentException("规则历史版本不存在");
        }
        Properties properties = readProperties(snapshot);
        applySettings(properties);
        version.set(Math.max(version.get(), targetVersion));
        changed();
    }

    public synchronized void removeCustomRule(String id) throws IOException {
        List<RuleDefinition> updated = customRules.stream().filter(rule -> !rule.id().equals(id)).toList();
        if (updated.size() == customRules.size()) {
            throw new IllegalArgumentException("自定义规则不存在");
        }
        customRules = updated;
        Set<String> disabled = new HashSet<>(disabledRuleIds);
        disabled.remove(id);
        disabledRuleIds = Set.copyOf(disabled);
        changed();
    }

    private static CustomRuleSpec toSpec(RuleDefinition rule) {
        return new CustomRuleSpec(rule.id(), rule.label(), rule.category(),
                rule.priority(), rule.pattern().pattern());
    }

    private static String normalizeCustomId(String requestedId) {
        String id = requestedId == null ? "" : requestedId.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        if (id.isBlank()) {
            throw new IllegalArgumentException("自定义规则ID不能为空");
        }
        if (!id.startsWith("CUSTOM_")) {
            id = "CUSTOM_" + id;
        }
        if (id.length() > 67) {
            throw new IllegalArgumentException("自定义规则ID不能超过60个字符");
        }
        return id;
    }

    private static RuleDefinition customRule(String id, String label, String category,
            int priority, String regex) {
        String safeLabel = label == null ? "" : label.trim();
        if (safeLabel.isBlank() || safeLabel.length() > 80) {
            throw new IllegalArgumentException("规则名称应为1至80个字符");
        }
        String safeCategory = category == null || category.isBlank() ? "custom"
                : category.trim().replaceAll("[^a-zA-Z0-9_]", "_");
        RedactionPattern pattern = compileCustomPattern(regex);
        return new RuleDefinition(id, safeLabel, safeCategory,
                Math.max(1, Math.min(999, priority)), pattern, 0, Validators::always);
    }

    private static RedactionPattern compileCustomPattern(String regex) {
        if (regex == null || regex.isBlank() || regex.length() > 500) {
            throw new IllegalArgumentException("正则表达式应为1至500个字符");
        }
        if (NESTED_UNBOUNDED_QUANTIFIER.matcher(regex).find()) {
            throw new IllegalArgumentException("正则表达式包含嵌套无限量词，可能导致处理超时");
        }
        if (NUMERIC_BACK_REFERENCE.matcher(regex).find()) {
            throw new IllegalArgumentException("自定义规则不允许数字反向引用");
        }
        try {
            RedactionPattern pattern = RedactionPattern.untrustedLinear(regex);
            if (pattern.matcher("").find()) {
                throw new IllegalArgumentException("自定义规则不能匹配空字符串");
            }
            return pattern;
        } catch (com.google.re2j.PatternSyntaxException ex) {
            throw new IllegalArgumentException("正则表达式不受支持或语法错误：" + ex.getMessage());
        }
    }

    public List<SensitiveMatch> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<SensitiveMatch> candidates = new ArrayList<>();
        List<String> whitelistSnapshot = whitelist;
        for (RuleDefinition rule : rules()) {
            if (!isEnabled(rule.id())) {
                continue;
            }
            RedactionPattern.Match matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                int start = matcher.start(rule.captureGroup());
                int end = matcher.end(rule.captureGroup());
                String value = matcher.group(rule.captureGroup());
                if (start >= 0 && end > start && !isWhitelisted(value, whitelistSnapshot)
                        && rule.validator().isValid(value, text, start, end)) {
                    candidates.add(new SensitiveMatch(rule.id(), rule.label(), rule.category(),
                            rule.priority(), start, end));
                }
            }
        }
        for (ChineseAdministrativeDivisionLexicon.LexiconMatch match
                : administrativeDivisionLexicon.find(text, this::isEnabled,
                value -> isWhitelisted(value, whitelistSnapshot))) {
            candidates.add(new SensitiveMatch(match.ruleId(), match.label(), "geography_location",
                    match.priority(), match.start(), match.end()));
        }
        for (String value : blacklist) {
            int from = 0;
            while (from < text.length()) {
                int start = text.indexOf(value, from);
                if (start < 0) {
                    break;
                }
                candidates.add(new SensitiveMatch("BLACKLIST", "黑名单", "custom", 1_000,
                        start, start + value.length()));
                from = start + Math.max(1, value.length());
            }
        }
        candidates.sort(Comparator
                .comparingInt(SensitiveMatch::priority).reversed()
                .thenComparing(Comparator.comparingInt(SensitiveMatch::length).reversed())
                .thenComparingInt(SensitiveMatch::start));

        boolean[] claimed = new boolean[text.length()];
        List<SensitiveMatch> selected = new ArrayList<>();
        for (SensitiveMatch candidate : candidates) {
            boolean overlaps = false;
            for (int i = candidate.start(); i < candidate.end(); i++) {
                if (claimed[i]) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                selected.add(candidate);
                for (int i = candidate.start(); i < candidate.end(); i++) {
                    claimed[i] = true;
                }
            }
        }
        selected.sort(Comparator.comparingInt(SensitiveMatch::start));
        return Collections.unmodifiableList(selected);
    }

    public RedactionResult redact(String text) {
        if (text == null) {
            return new RedactionResult("", List.of(), Map.of());
        }
        List<SensitiveMatch> matches = detect(text);
        if (matches.isEmpty()) {
            return new RedactionResult(text, matches, Map.of());
        }
        char[] output = text.toCharArray();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SensitiveMatch match : matches) {
            String original = text.substring(match.start(), match.end());
            String replacement = pseudonymKey == null
                    ? mask(original) : stablePseudonym(original, match.ruleId());
            for (int i = match.start(); i < match.end(); i++) {
                output[i] = replacement.charAt(i - match.start());
            }
            counts.merge(match.ruleId(), 1, Integer::sum);
        }
        return new RedactionResult(new String(output), matches,
                Collections.unmodifiableMap(counts));
    }

    private static char maskCharacter(char original) {
        if (Character.isWhitespace(original)) {
            return original;
        }
        if (Character.isLetterOrDigit(original) || Character.UnicodeScript.of(original) == Character.UnicodeScript.HAN) {
            return '＊';
        }
        return original;
    }

    private static String mask(String value) {
        char[] masked = value.toCharArray();
        for (int i = 0; i < masked.length; i++) {
            masked[i] = maskCharacter(masked[i]);
        }
        return new String(masked);
    }

    private String stablePseudonym(String value, String ruleId) {
        byte[] digest;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pseudonymKey, "HmacSHA256"));
            mac.update(ruleId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法生成一致替换值", ex);
        }
        char[] output = value.toCharArray();
        String hanAlphabet = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥春夏秋冬东南西北中";
        for (int i = 0; i < output.length; i++) {
            char original = output[i];
            int valueByte = Byte.toUnsignedInt(digest[i % digest.length]);
            if (Character.isWhitespace(original)) {
                continue;
            }
            if (original >= '0' && original <= '9') {
                output[i] = (char) ('0' + valueByte % 10);
            } else if (original >= 'A' && original <= 'Z') {
                output[i] = (char) ('A' + valueByte % 26);
            } else if (original >= 'a' && original <= 'z') {
                output[i] = (char) ('a' + valueByte % 26);
            } else if (Character.UnicodeScript.of(original) == Character.UnicodeScript.HAN) {
                output[i] = hanAlphabet.charAt(valueByte % hanAlphabet.length());
            } else if (Character.isLetterOrDigit(original)) {
                output[i] = '＊';
            }
        }
        if (Arrays.equals(value.toCharArray(), output)) {
            for (int i = 0; i < output.length; i++) {
                if (output[i] >= '0' && output[i] <= '9') {
                    output[i] = (char) ('0' + (output[i] - '0' + 1) % 10);
                    break;
                }
                if (Character.isLetter(output[i]) || Character.UnicodeScript.of(output[i]) == Character.UnicodeScript.HAN) {
                    output[i] = '甲';
                    break;
                }
            }
        }
        Arrays.fill(digest, (byte) 0);
        return new String(output);
    }

    private static boolean isWhitelisted(String value, List<String> whitelist) {
        return whitelist.stream().anyMatch(item -> item.equalsIgnoreCase(value));
    }

    private static List<String> sanitizeList(List<String> values, int maximum) {
        if (values == null) {
            return List.of();
        }
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String cleaned = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ").trim();
            if (cleaned.isBlank()) {
                continue;
            }
            if (cleaned.length() > 200) {
                throw new IllegalArgumentException("黑白名单单项不能超过200个字符");
            }
            unique.putIfAbsent(cleaned.toLowerCase(Locale.ROOT), cleaned);
            if (unique.size() > maximum) {
                throw new IllegalArgumentException("黑白名单最多" + maximum + "项");
            }
        }
        return List.copyOf(unique.values());
    }

    private void changed() throws IOException {
        version.incrementAndGet();
        saveSettings();
    }

    private synchronized void saveSettings() throws IOException {
        if (settingsFile == null) {
            return;
        }
        Properties properties = settingsProperties();
        writeProperties(settingsFile, properties);
        Path history = historyDirectory();
        if (history != null) {
            Files.createDirectories(history);
            writeProperties(history.resolve("rules-v" + version.get() + ".properties"), properties);
            pruneHistory(history);
        }
    }

    private Properties settingsProperties() {
        Properties properties = new Properties();
        properties.setProperty("version", Long.toString(version.get()));
        properties.setProperty("disabled", String.join(",", disabledRuleIds));
        writeList(properties, "black", blacklist);
        writeList(properties, "white", whitelist);
        for (int i = 0; i < customRules.size(); i++) {
            RuleDefinition rule = customRules.get(i);
            String prefix = "custom." + i + ".";
            properties.setProperty(prefix + "id", rule.id());
            properties.setProperty(prefix + "label", rule.label());
            properties.setProperty(prefix + "category", rule.category());
            properties.setProperty(prefix + "priority", Integer.toString(rule.priority()));
            properties.setProperty(prefix + "regex", rule.pattern().pattern());
        }
        return properties;
    }

    private static void writeProperties(Path target, Properties properties) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer, "local redaction rule settings; may contain user-defined sensitive terms");
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void loadSettings() {
        if (!Files.isRegularFile(settingsFile)) {
            return;
        }
        try {
            applySettings(readProperties(settingsFile));
        } catch (Exception ex) {
            disabledRuleIds = Set.of();
            blacklist = List.of();
            whitelist = List.of();
            customRules = List.of();
        }
    }

    private static Properties readProperties(Path source) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private void applySettings(Properties properties) {
        String disabled = properties.getProperty("disabled", "");
        disabledRuleIds = disabled.isBlank() ? Set.of() : Set.of(disabled.split(","));
        blacklist = readList(properties, "black");
        whitelist = readList(properties, "white");
        List<RuleDefinition> loadedCustom = new ArrayList<>();
        for (int i = 0; i < MAX_CUSTOM_RULES; i++) {
            String prefix = "custom." + i + ".";
            String id = properties.getProperty(prefix + "id");
            if (id == null) {
                continue;
            }
            loadedCustom.add(customRule(normalizeCustomId(id),
                    properties.getProperty(prefix + "label", id),
                    properties.getProperty(prefix + "category", "custom"),
                    Integer.parseInt(properties.getProperty(prefix + "priority", "50")),
                    properties.getProperty(prefix + "regex")));
        }
        customRules = List.copyOf(loadedCustom);
        version.set(Long.parseLong(properties.getProperty("version", "1")));
    }

    private Path historyDirectory() {
        if (settingsFile == null) {
            return null;
        }
        return settingsFile.resolveSibling("rule-history");
    }

    private static void pruneHistory(Path history) throws IOException {
        List<Path> snapshots;
        try (Stream<Path> paths = Files.list(history)) {
            snapshots = paths.filter(path -> path.getFileName().toString()
                            .matches("rules-v[0-9]+\\.properties"))
                    .sorted(Comparator.comparingLong(RuleEngine::snapshotVersion).reversed())
                    .toList();
        }
        for (int i = MAX_RULE_HISTORY; i < snapshots.size(); i++) {
            Files.deleteIfExists(snapshots.get(i));
        }
    }

    private static long snapshotVersion(Path path) {
        String name = path.getFileName().toString();
        return Long.parseLong(name.substring(7, name.length() - 11));
    }

    private static void writeList(Properties properties, String prefix, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            properties.setProperty(prefix + "." + i, values.get(i));
        }
    }

    private static List<String> readList(Properties properties, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            String value = properties.getProperty(prefix + "." + i);
            if (value == null) {
                continue;
            }
            values.add(value);
        }
        return List.copyOf(values);
    }
}
