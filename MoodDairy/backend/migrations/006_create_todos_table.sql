-- Create todos table for calendar day tasks

CREATE TABLE IF NOT EXISTS public.todos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    todo_date DATE NOT NULL,
    title VARCHAR(200) NOT NULL,
    note TEXT,
    is_done SMALLINT NOT NULL DEFAULT 0,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_todos_user
        FOREIGN KEY (user_id)
        REFERENCES public.users(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_todos_is_done
        CHECK (is_done IN (0, 1))
);

CREATE INDEX IF NOT EXISTS idx_todos_user_date
    ON public.todos(user_id, todo_date);

CREATE INDEX IF NOT EXISTS idx_todos_user_date_done_sort
    ON public.todos(user_id, todo_date, is_done, sort_order);
