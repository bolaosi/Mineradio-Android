"""Independent QR decoder for browser PNG output. Input manifest contains fake test URLs only."""
import json
import os
import sys
from pathlib import Path

project = Path(__file__).resolve().parents[1]
decoder_path = os.environ.get('MINERADIO_QR_DECODER_PATH', str(project.parent / '.local-tools' / 'qr-check'))
sys.path.insert(0, decoder_path)
import zxingcpp
from PIL import Image

manifest = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
results = []
for item in manifest:
    image_path = Path(item['path'])
    assert image_path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'Not a PNG'
    with Image.open(image_path) as source:
        image = source.convert('RGB')
        assert source.format == 'PNG'
        assert image.width == image.height and image.width >= 200
        for point in [(0, 0), (image.width - 1, 0), (0, image.height - 1), (image.width - 1, image.height - 1)]:
            assert image.getpixel(point) == (255, 255, 255), 'Quiet zone must be white'
        decoded = zxingcpp.read_barcode(image)
        assert decoded is not None, 'No independently decodable QR found'
        assert decoded.text == item['text'], 'Decoded contents differ from the input text'
        results.append({'width': image.width, 'height': image.height, 'decodedMatches': True})
print(json.dumps({'passed': True, 'decoder': 'zxing-cpp', 'images': results}, ensure_ascii=True))
