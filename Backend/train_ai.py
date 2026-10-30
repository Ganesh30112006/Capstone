import json
import random
import time
import urllib.request
import urllib.error

url = "http://127.0.0.1:8000/api/ai/train"

print("Starting continuous GPU training loop...")
epoch = 1

while True:
    readings = []
    # Generate batch
    for _ in range(128):
        readings.append({
            "temperature": round(random.gauss(30.0, 5.0), 2),
            "humidity": round(random.gauss(65.0, 10.0), 2),
            "pressure": round(random.gauss(1010.0, 5.0), 2),
            "airQuality": round(random.gauss(80.0, 25.0), 2),
            "oxygen": round(random.gauss(20.9, 0.2), 2),
        })

    payload = json.dumps({"readings": readings}).encode('utf-8')
    req = urllib.request.Request(url, data=payload, headers={'Content-Type': 'application/json'}, method='POST')

    try:
        with urllib.request.urlopen(req) as response:
            info = json.loads(response.read())
            print(f"[Epoch {epoch}] Trained 128 samples. Model MSE loss will optimize.")
            epoch += 1
    except urllib.error.URLError as e:
        print(f"Error connecting to AI service at {url}.")
        time.sleep(5)
        continue
    
    # Tiny sleep to avoid completely overwhelming the CPU/GPU
    time.sleep(0.5)
