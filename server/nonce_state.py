"""网关与 instance 之间共享 nonce 的小模块。

为什么需要它：server/main.py 是以 `python server/main.py` 运行的，
此时它的模块名是 `__main__`。如果 instance.py 里写
`from server.main import nonce`，Python 会**再执行一遍 main.py** 得到
另一个模块对象，其中的 nonce 恒为 None —— 于是网关发给 worker 的
X-Nonce 头会缺失，worker 端 check_nonce() 直接返回
401 "Nonce does not match"，翻译全部失败。

同一进程内共享可变状态，用这个模块最省事：
  - server/main.py:prepare() 里写入 nonce_state.nonce = nonce
  - server/instance.py:_nonce_headers() 里读取它
"""

nonce = None
