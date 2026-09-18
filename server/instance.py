from asyncio import Event, Lock
from typing import List

from PIL import Image
from pydantic import BaseModel

from manga_translator import Config
from server.sent_data_internal import fetch_data_stream, NotifyType, fetch_data


def _nonce_headers() -> dict:
    """网关 -> worker 的内部调用必须带上 X-Nonce。

    上游这里漏了：fetch_data() 默认 headers={}，而 worker 的
    manga_translator/mode/share.py:check_nonce() 会校验该头，
    缺失即返回 401 "Nonce does not match"，导致 --start-instance 模式下
    所有翻译请求全部失败（页面能打开、一翻译就 500）。此处补上。
    延迟 import，避免与 server.main 形成循环依赖。
    """
    try:
        from server import nonce_state
        return {"X-Nonce": nonce_state.nonce} if nonce_state.nonce else {}
    except Exception:
        return {}

class ExecutorInstance(BaseModel):
    ip: str
    port: int
    busy: bool = False

    def free_executor(self):
        self.busy = False

    async def sent(self, image: Image, config: Config):
        return await fetch_data("http://"+self.ip+":"+str(self.port)+"/simple_execute/translate", image, config,
                                headers=_nonce_headers())

    async def sent_stream(self, image: Image, config: Config, sender: NotifyType):
        await fetch_data_stream("http://"+self.ip+":"+str(self.port)+"/execute/translate", image, config, sender,
                                headers=_nonce_headers())

    async def sent_batch(self, images: List[Image.Image], config: Config, batch_size: int):
        """发送批量翻译请求"""
        return await fetch_data("http://"+self.ip+":"+str(self.port)+"/simple_execute/translate_batch", 
                               {"images": images, "config": config, "batch_size": batch_size},
                               headers=_nonce_headers())

    async def sent_batch_stream(self, images: List[Image.Image], config: Config, batch_size: int, sender: NotifyType):
        """发送批量翻译流式请求"""
        await fetch_data_stream("http://"+self.ip+":"+str(self.port)+"/execute/translate_batch",
                               {"images": images, "config": config, "batch_size": batch_size}, config, sender)

class Executors:
    def __init__(self):
        self.list: List[ExecutorInstance] = []
        self.lock: Lock = Lock()
        self.event = Event()

    def register(self, instance: ExecutorInstance):
        self.list.append(instance)

    def free_executors(self) -> int:
        return len([item for item in self.list if not item.busy])

    async def _find_instance(self):
        while True:
            instance = next((x for x in self.list if x.busy == False), None)
            if instance is not None:
                return instance
            #todo: cricial error: warn should never happen
            await self.event.wait()

    async def find_executor(self) -> ExecutorInstance:
        async with self.lock:  # Using async with for lock management
            instance = await self._find_instance()
            instance.busy = True
            return instance

    async def free_executor(self, instance: ExecutorInstance):
        from server.myqueue import task_queue
        instance.free_executor()
        self.event.set()
        self.event.clear()
        await task_queue.update_event()

executor_instances: Executors = Executors()
