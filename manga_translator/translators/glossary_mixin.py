"""术语表（glossary）支持 —— 供所有 GPT 类翻译器复用。

背景：上游把这套逻辑（多格式字典解析 + 相关术语筛选 + 注入 system message）只实现在
`chatgpt.py` 的 OpenAITranslator 里，而 `DeepseekTranslator` / `GeminiTranslator` /
`GroqTranslator` / `CustomOpenAiTranslator` 继承的是 `common_gpt.CommonGPTTranslator`
（另一个平行类），于是它们设了 OPENAI_GLOSSARY_PATH 也**静默无效**。

这里把逻辑原样抽成 mixin 给 CommonGPTTranslator 用，chatgpt 那条线完全不改，避免回归。

字典路径解析优先级：GPT_GLOSSARY_PATH -> OPENAI_GLOSSARY_PATH -> ./dict/mit_glossary.txt
支持的格式（自动识别）：MIT（tab/空格）、Galtransl（tab/四空格）、Sakura（src->dst）
"""

import os
import re

# 缺文件时的警告只打一次
_GLOSSARY_WARNING_SHOWN = False

# 与 config_gpt.ConfigGPT._GLOSSARY_SYSTEM_TEMPLATE 等价，给没有 ConfigGPT 的类兜底
_DEFAULT_GLOSSARY_TEMPLATE = (
    "Please translate the text based on the following glossary, adhering to the corresponding relationships and notes in the glossary:\n"
    "(Note.If the target language or source text is not in the glossary, please ignore the glossary)\n"
    "{glossary_text}"
)


class GlossaryMixin:
    """给翻译器加上术语表能力。使用方需具备 self.logger（CommonTranslator 已提供）。"""

    # 跨页上下文（前几页的原文/译文），由 manga_translator._dispatch_with_context 注入
    prev_context: str = ""

    def set_prev_context(self, text: str = ""):
        self.prev_context = text or ""

    def init_glossary(self, dict_path: str = None):
        path = dict_path or os.getenv('GPT_GLOSSARY_PATH') or os.getenv('OPENAI_GLOSSARY_PATH') \
            or './dict/mit_glossary.txt'
        self.dict_path = path
        self.glossary_entries = {}

        user_set = bool(os.getenv('GPT_GLOSSARY_PATH') or os.getenv('OPENAI_GLOSSARY_PATH'))
        global _GLOSSARY_WARNING_SHOWN
        if os.path.exists(path):
            self.glossary_entries = self.load_glossary(path)
            if self.glossary_entries:
                self.logger.info(f"Loaded glossary: {path} ({len(self.glossary_entries)} entries)")
        elif user_set and not _GLOSSARY_WARNING_SHOWN:
            self.logger.warning(f"The glossary file does not exist: {path}")
            _GLOSSARY_WARNING_SHOWN = True

    # ---------------------------------------------------------------- 解析
    def detect_type(self, dic_path):
        """检测字典格式：sakura / galtransl / mit / unknown"""
        with open(dic_path, encoding="utf8") as f:
            dic_lines = f.readlines()
        self.logger.debug(f"Detecting dictionary type: {dic_path}")
        if len(dic_lines) == 0:
            return "unknown"

        # Sakura：每行都是 src->dst
        is_sakura, sakura_line_count = True, 0
        for line in dic_lines:
            line = line.strip()
            if not line or line.startswith("\\\\") or line.startswith("//"):
                continue
            if "->" in line:
                sakura_line_count += 1
            else:
                is_sakura = False
                break
        if is_sakura and sakura_line_count > 0:
            return "sakura"

        # Galtransl：tab 或四空格分隔
        is_galtransl, galtransl_line_count = True, 0
        for line in dic_lines:
            line = line.strip()
            if not line or line.startswith("\\\\") or line.startswith("//"):
                continue
            if "\t" in line or "    " in line:
                galtransl_line_count += 1
            else:
                is_galtransl = False
                break
        if is_galtransl and galtransl_line_count > 0:
            return "galtransl"

        # MIT：最宽松（tab 或任意空白分割出两段）
        is_mit, mit_line_count = True, 0
        for line in dic_lines:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("//"):
                continue
            if "->" in line:
                is_mit = False
                break
            parts = line.split("\t", 1)
            if len(parts) == 1:
                parts = line.split(None, 1)
            if len(parts) >= 2:
                mit_line_count += 1
            else:
                is_mit = False
                break
        if is_mit and mit_line_count > 0:
            return "mit"

        return "unknown"

    def load_glossary(self, path):
        if not os.path.exists(path):
            global _GLOSSARY_WARNING_SHOWN
            if not _GLOSSARY_WARNING_SHOWN:
                self.logger.warning(f"The glossary file does not exist: {path}")
                _GLOSSARY_WARNING_SHOWN = True
            return {}
        dict_type = self.detect_type(path)
        if dict_type == "galtransl":
            return self.load_galtransl_dic(path)
        elif dict_type == "sakura":
            return self.load_sakura_dict(path)
        elif dict_type == "mit":
            return self.load_mit_dict(path)
        else:
            self.logger.warning(f"Unknown glossary format: {path}")
            return {}

    def load_mit_dict(self, dic_path):
        """MIT 格式：`原文<tab>译文 [#注释]`，源词按正则校验"""
        with open(dic_path, encoding="utf8") as f:
            dic_lines = f.readlines()
        if len(dic_lines) == 0:
            return {}

        dic_name = os.path.basename(os.path.abspath(dic_path))
        dict_count, regex_errors = 0, 0
        glossary_entries = {}

        for line_number, line in enumerate(dic_lines, start=1):
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("//"):
                continue

            comment = ""
            if '#' in line:
                parts = line.split('#', 1)
                line = parts[0].strip()
                comment = "#" + parts[1]
            elif '//' in line:
                parts = line.split('//', 1)
                line = parts[0].strip()
                comment = "//" + parts[1]

            parts = line.split("\t", 1)
            if len(parts) == 1:
                parts = line.split(None, 1)

            if len(parts) < 2:
                self.logger.debug(f"Skipping lines with a single word: {line}")
                continue
            src = parts[0].strip().replace('_', ' ')
            dst = parts[1].strip().replace('_', ' ')

            try:
                re.compile(src)
                glossary_entries[src] = f"{dst} {comment}" if comment else dst
                dict_count += 1
            except re.error as e:
                regex_errors += 1
                self.logger.warning(f"Regular expression error on line {line_number}: '{src}' - {e}")

        self.logger.info(
            f"Loading MIT format dictionary: {dic_name} containing {dict_count} entries, "
            f"found {regex_errors} regular expression errors"
        )
        return glossary_entries

    def load_galtransl_dic(self, dic_path):
        glossary_entries = {}
        try:
            with open(dic_path, encoding="utf8") as f:
                dic_lines = f.readlines()
            if len(dic_lines) == 0:
                return {}
            dic_name = os.path.basename(os.path.abspath(dic_path))
            count = 0
            for line in dic_lines:
                if line.startswith("\\\\") or line.startswith("//") or line.strip() == "":
                    continue
                parts = line.split("\t")
                if len(parts) != 2:
                    parts = line.split("    ", 1)
                if len(parts) == 2:
                    glossary_entries[parts[0].strip()] = parts[1].strip()
                    count += 1
                else:
                    self.logger.debug(f"Skipping lines that do not conform to the format.: {line.strip()}")
            self.logger.info(f"Loading Galtransl format dictionary: {dic_name} containing {count} entries")
            return glossary_entries
        except Exception as e:
            self.logger.error(f"Error loading Galtransl dictionary: {e}")
            return {}

    def load_sakura_dict(self, dic_path):
        glossary_entries = {}
        try:
            with open(dic_path, encoding="utf8") as f:
                dic_lines = f.readlines()
            if len(dic_lines) == 0:
                return {}
            dic_name = os.path.basename(os.path.abspath(dic_path))
            count = 0
            for line in dic_lines:
                line = line.strip()
                if line.startswith("\\\\") or line.startswith("//") or line == "":
                    continue
                if "->" in line:
                    parts = line.split("->", 1)
                    if len(parts) == 2:
                        glossary_entries[parts[0].strip()] = parts[1].strip()
                        count += 1
            self.logger.info(f"Loading Sakura format dictionary: {dic_name} containing {count} entries")
            return glossary_entries
        except Exception as e:
            self.logger.error(f"Error loading Sakura dictionary: {e}")
            return {}

    # ---------------------------------------------------------------- 筛选
    def extract_relevant_terms(self, text):
        """只挑出与本次 query 相关的术语，避免整表塞进 prompt 浪费 token、稀释 system 权重。"""
        relevant_terms = {}

        def levenshtein_distance(s1, s2):
            if len(s1) < len(s2):
                return levenshtein_distance(s2, s1)
            if len(s2) == 0:
                return len(s1)
            previous_row = range(len(s2) + 1)
            for i, c1 in enumerate(s1):
                current_row = [i + 1]
                for j, c2 in enumerate(s2):
                    insertions = previous_row[j + 1] + 1
                    deletions = current_row[j] + 1
                    substitutions = previous_row[j] + (c1 != c2)
                    current_row.append(min(insertions, deletions, substitutions))
                previous_row = current_row
            return previous_row[-1]

        def normalize_japanese(s):
            result = ""
            small_to_normal = {
                'ァ': 'ア', 'ィ': 'イ', 'ゥ': 'ウ', 'ェ': 'エ', 'ォ': 'オ',
                'ッ': 'ツ', 'ャ': 'ヤ', 'ュ': 'ユ', 'ョ': 'ヨ',
                'ぁ': 'あ', 'ぃ': 'い', 'ぅ': 'う', 'ぇ': 'え', 'ぉ': 'お',
                'っ': 'つ', 'ゃ': 'や', 'ゅ': 'ゆ', 'ょ': 'よ'
            }
            for char in s:
                char = small_to_normal.get(char, char)
                if 0x30A0 <= ord(char) <= 0x30FF:      # 片假名 -> 平假名
                    result += chr(ord(char) - 0x60)
                else:
                    result += char
            return result

        def japanese_levenshtein_distance(s1, s2):
            return levenshtein_distance(normalize_japanese(s1), normalize_japanese(s2))

        def normalize_term(term):
            term = re.sub(r'[^\w\s]', '', term)
            term = term.lower()
            return normalize_japanese(term)

        def partial_match(text_, term):
            return normalize_term(term) in normalize_term(text_)

        def is_japanese_similar(text_, term, threshold=2):
            normalized_text, normalized_term = normalize_term(text_), normalize_term(term)
            if len(normalized_term) <= 2:
                threshold = 0
            elif len(normalized_term) <= 4:
                threshold = 1
            return japanese_levenshtein_distance(normalized_text, normalized_term) <= threshold

        def is_general_similar(text_, term, threshold=2):
            normalized_text, normalized_term = normalize_term(text_), normalize_term(term)
            threshold = max(0, min(len(normalized_term) // 8, 3))
            if len(normalized_text) > len(normalized_term) * 5:
                if len(normalized_term) <= 8:
                    window_size = len(normalized_term)
                elif len(normalized_term) <= 16:
                    window_size = len(normalized_term) + 1
                else:
                    window_size = len(normalized_term) + 2
                min_distance = float('inf')
                for i in range(max(0, len(normalized_text) - window_size + 1)):
                    window = normalized_text[i:i + window_size]
                    min_distance = min(min_distance, levenshtein_distance(window, normalized_term))
                return min_distance <= threshold
            return levenshtein_distance(normalized_text, normalized_term) <= threshold

        for term, translation in self.glossary_entries.items():
            if term in text or term.replace(" ", "") in text:
                relevant_terms[term] = translation
                continue
            if any(0x3040 <= ord(c) <= 0x30FF for c in term):     # 含日文
                if is_japanese_similar(text, term):
                    relevant_terms[term] = translation
                    continue
            elif is_general_similar(text, term):
                relevant_terms[term] = translation
                continue
            if partial_match(text, term):
                relevant_terms[term] = translation
                continue
            try:
                if re.compile(term, re.IGNORECASE).search(text):
                    relevant_terms[term] = translation
            except re.error:
                pass

        return relevant_terms

    # ---------------------------------------------------------------- 注入
    def build_glossary_message(self, prompt: str):
        """返回 (是否有术语, system message 文本)，供 _assemble_request 注入。"""
        if not getattr(self, 'glossary_entries', None):
            return False, None
        try:
            relevant_terms = self.extract_relevant_terms(prompt)
        except Exception as e:
            self.logger.warning(f"Glossary matching failed: {e}")
            return False, None
        if not relevant_terms:
            return False, None
        glossary_text = "\n".join(f"{term}->{translation}" for term, translation in relevant_terms.items())
        self.logger.info(f"Loaded {len(relevant_terms)} relevant terms from the glossary.")

        # 模板优先取 ConfigGPT 的 glossary_system_template；
        # 没有 ConfigGPT 的类（如 GroqTranslator 只继承 CommonTranslator）用内置默认模板。
        template = None
        try:
            template = self.glossary_system_template
        except Exception:
            template = None
        if not template:
            template = _DEFAULT_GLOSSARY_TEMPLATE
        return True, template.format(glossary_text=glossary_text)
