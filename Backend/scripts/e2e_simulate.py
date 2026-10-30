import boto3
import json
import uuid
from datetime import datetime, timezone

def send_simulated_telemetry():
    sqs = boto3.client(
        'sqs',
        region_name='us-east-1',
        endpoint_url='http://localhost:4566',
        aws_access_key_id='test',
        aws_secret_access_key='test'
    )
    
    # Needs to match LocalStack setup for iot-telemetry-queue and iot-ai-queue
    try:
        telemetry_queue = sqs.get_queue_url(QueueName='iot-telemetry-queue')['QueueUrl']
        
        payload = {
            "messageId": str(uuid.uuid4()),
            "deviceUid": "ESP32-SIMULATED",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "temperature": 46.5,  # Trigger anomaly
            "humidity": 65.0,
            "airQuality": 88.0,
            "oxygen": 21.0,
            "pressure": 1012.0,
            "rain": False,
            "latitude": 40.7128,
            "longitude": -74.0060
        }
        
        sqs.send_message(QueueUrl=telemetry_queue, MessageBody=json.dumps(payload))
        print(f"Sent simulated telemetry: {payload['messageId']}")
        
    except sqs.exceptions.QueueDoesNotExist:
        print("Test Queues do not exist in LocalStack. Run docker-compose up first.")

if __name__ == "__main__":
    send_simulated_telemetry()
