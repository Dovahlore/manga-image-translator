import json
import pickle
from typing import Mapping, Optional, Callable

import aiohttp
from PIL.Image import Image
from fastapi import HTTPException

from manga_translator import Config

NotifyType = Optional[Callable[[int, Optional[bytes]], None]]

async def fetch_data_stream(url, image: Image, config: Config, sender: NotifyType, headers: Mapping[str, str] = {}):
    attributes = {"image": image, "config": config}
    data = pickle.dumps(attributes)

    async with aiohttp.ClientSession() as session:
        async with session.post(url, data=data, headers=headers) as response:
            if response.status == 200:
                await process_stream(response, sender)
            else:
                raise HTTPException(response.status, detail=await response.text())

async def fetch_data(url, image: Image, config: Config, headers: Mapping[str, str] = {}):
    attributes = {"image": image, "config": config}
    data = pickle.dumps(attributes)

    async with aiohttp.ClientSession() as session:
        async with session.post(url, data=data, headers=headers) as response:
            if response.status == 200:
                raw = await response.read()
                # worker 的 /simple_execute/* 返回的是 pickle 字节流（manga_translator/mode/share.py:151），
                # 上游这里却用 json.loads(response.text()) 解析，于是必然抛
                # "'utf-8' codec can't decode byte 0x80（pickle 协议头）"，
                # 导致所有非流式翻译接口 500（流式接口走 streaming.py，不受影响）。
                # 这里先按 JSON 试（兼容其他实现），失败再按受限 pickle 反序列化。
                try:
                    return json.loads(raw.decode('utf-8'))
                except Exception:
                    pass
                try:
                    from manga_translator.mode.share import restricted_loads
                    return restricted_loads(raw)
                except Exception as e:
                    raise HTTPException(502, detail='Invalid response from upstream: %s' % e)
            else:
                raise HTTPException(response.status, detail=await response.text())

async def process_stream(response, sender: NotifyType):
    buffer = b''

    async for chunk in response.content.iter_any():
        if chunk:
            buffer += chunk
            buffer = handle_buffer(buffer, sender)



def handle_buffer(buffer, sender: NotifyType):
    while len(buffer) >= 5:
        status, expected_size = extract_header(buffer)

        if len(buffer) >= 5 + expected_size:
            data = buffer[5:5 + expected_size]
            sender(status, data)
            buffer = buffer[5 + expected_size:]
        else:
            break
    return buffer


def extract_header(buffer):
    """Extract the status and expected size from the buffer."""
    status = int.from_bytes(buffer[0:1], byteorder='big')
    expected_size = int.from_bytes(buffer[1:5], byteorder='big')
    return status, expected_size

