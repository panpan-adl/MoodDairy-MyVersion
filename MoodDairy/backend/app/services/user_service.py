"""
用户服务层

提供用户相关的业务逻辑处理，包括：
- 用户认证（登录）
- 用户注册
- 用户信息查询和更新
"""
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from typing import Optional
from datetime import date

from app.models.database import User
from app.models.schemas import RegisterRequest, UpdateUserRequest
from app.security.password import hash_password, needs_rehash, verify_password


class UserService:
    """用户服务类"""
    
    def __init__(self, db: AsyncSession):
        """
        初始化用户服务
        
        Args:
            db: 数据库会话
        """
        self.db = db
    
    async def authenticate_user(
        self, 
        username: str, 
        password: str
    ) -> Optional[User]:
        """
        验证用户身份
        
        Args:
            username: 用户名
            password: 密码（明文）
            
        Returns:
            Optional[User]: 验证成功返回用户对象，失败返回None
            
        验证需求: 1.1, 1.2
        """
        # 查询用户
        stmt = select(User).where(User.username == username)
        result = await self.db.execute(stmt)
        user = result.scalar_one_or_none()
        
        if user and verify_password(password, user.password):
            if needs_rehash(user.password):
                user.password = hash_password(password)
                await self.db.commit()
                await self.db.refresh(user)
            return user
        
        return None
    
    async def create_user(
        self, 
        data: RegisterRequest
    ) -> Optional[User]:
        """
        创建新用户
        
        Args:
            data: 注册请求数据
            
        Returns:
            Optional[User]: 创建成功返回用户对象，用户名已存在返回None
            
        验证需求: 2.1, 2.2
        """
        # 检查用户名是否已存在
        stmt = select(User).where(User.username == data.username)
        result = await self.db.execute(stmt)
        existing_user = result.scalar_one_or_none()
        
        if existing_user:
            return None
        
        # 创建用户对象
        user = User(
            username=data.username,
            password=hash_password(data.password),
            nickname=data.nickname,
            gender=0,  # 默认未知
            status=1   # 默认正常
        )
        
        # 添加到数据库
        self.db.add(user)
        await self.db.commit()
        await self.db.refresh(user)
        
        return user
    
    async def get_user(
        self, 
        user_id: int
    ) -> Optional[User]:
        """
        获取用户信息
        
        Args:
            user_id: 用户ID
            
        Returns:
            Optional[User]: 用户对象，如果不存在则返回None
            
        验证需求: 3.1
        """
        stmt = select(User).where(User.id == user_id)
        result = await self.db.execute(stmt)
        return result.scalar_one_or_none()
    
    async def update_user(
        self, 
        user_id: int, 
        data: UpdateUserRequest
    ) -> Optional[User]:
        """
        更新用户信息
        
        Args:
            user_id: 用户ID
            data: 更新用户请求数据
            
        Returns:
            Optional[User]: 更新后的用户对象，如果不存在则返回None
            
        验证需求: 4.2
        """
        # 查询用户
        user = await self.get_user(user_id)
        
        if not user:
            return None
        
        # 更新字段（只更新非None的字段）
        update_data = data.model_dump(exclude_unset=True)
        for field, value in update_data.items():
            setattr(user, field, value)
        
        await self.db.commit()
        await self.db.refresh(user)
        
        return user
    
    async def update_avatar(
        self, 
        user_id: int, 
        avatar_url: str
    ) -> Optional[User]:
        """
        更新用户头像
        
        Args:
            user_id: 用户ID
            avatar_url: 头像URL
            
        Returns:
            Optional[User]: 更新后的用户对象，如果不存在则返回None
            
        验证需求: 5.3
        """
        # 查询用户
        user = await self.get_user(user_id)
        
        if not user:
            return None
        
        # 更新头像
        user.avatar = avatar_url
        
        await self.db.commit()
        await self.db.refresh(user)
        
        return user
