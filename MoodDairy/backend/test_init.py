import asyncio
import sys
sys.path.insert(0, '.')

async def main():
    from app.database.connection import init_db, engine, _active_db_url
    import logging
    logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    
    print("Initializing DB...")
    await init_db()
    print(f"Active DB: {_active_db_url}")
    print("Engine URL:", str(engine.url))

asyncio.run(main())
