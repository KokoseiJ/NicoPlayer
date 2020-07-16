import json
import random
import requests
import subprocess
from bs4 import BeautifulSoup as bs

def nico_get_mylist(url):
    name = None
    r = requests.get(url)
    soup = bs(r.text, "html.parser")
    name = soup.find("div", id = "PAGEBODY")\
        .find_all("script")[-4]\
        .string\
        .split("MylistGroup.preloadSingle(")[1]\
        .split("name:")[1].split("\"")[1].split("\"")[0]

    vidlist = []
    prev_json = None
    page = 0
    while True:
        page += 1
        r = requests.get(f"{url}#+page={page}")
        soup = bs(r.text, "html.parser")
        listtxt = soup.find("div", id = "PAGEBODY")\
            .find_all("script")[-4]\
            .string\
            .split("Mylist.preload")[1]\
            .split(", ", 1)[1].split(");")[0]
        jsonlist = json.loads(listtxt)
        if jsonlist == prev_json:
            break
        vidlist.extend([
            (   
                vid['item_data']['video_id'],
                {
                    "title": vid['item_data']['title'],
                    "thumbnail": vid['item_data']['thumbnail_url']
                }
            )
            for vid in jsonlist
            ])
        prev_json = jsonlist
    return name, vidlist

vidlist = nico_get_mylist(input("Mylist URL: "))[1]
random.shuffle(vidlist)

for vid in vidlist:
    print(f"Playing {vid[1]['title']}")
    subprocess.run(["java", "-jar", "NicoPlayer_Play.jar"], input = ("https://www.nicovideo.jp/watch/" + vid[0]).encode())